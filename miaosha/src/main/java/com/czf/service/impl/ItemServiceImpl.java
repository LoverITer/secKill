package com.czf.service.impl;

import com.czf.dao.ItemDOMapper;
import com.czf.dao.ItemStockDOMapper;
import com.czf.dao.StockLogDOMapper;
import com.czf.dataobject.ItemDO;
import com.czf.dataobject.ItemStockDO;
import com.czf.dataobject.StockLogDO;
import com.czf.error.BusinessException;
import com.czf.error.EmBusinessError;
import com.czf.mq.MqProducer;
import com.czf.service.ItemService;
import com.czf.service.PromoService;
import com.czf.service.model.ItemModel;
import com.czf.service.model.PromoModel;
import com.czf.validator.ValidationResult;
import com.czf.validator.ValidatorImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author czf
 * @Date 2020/3/10 11:15 下午
 */
@Service
public class ItemServiceImpl implements ItemService {
    /**
     * 创建商品
     *
     * @param itemModel
     * @return
     */

    @Autowired
    private ValidatorImpl validator;

    @Autowired
    private ItemDOMapper itemDOMapper;

    @Autowired
    private ItemStockDOMapper itemStockDOMapper;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private PromoService promoService;

    @Autowired
    private MqProducer mqProducer;

    @Autowired
    private StockLogDOMapper stockLogDOMapper;

    /**
     * 创建一个商品, 并写入数据库
     * @param itemModel
     * @return
     * @throws BusinessException
     */
    @Override
    @Transactional
    public ItemModel createItem(ItemModel itemModel) throws BusinessException {
        // 校验入参
        ValidationResult result = validator.validate(itemModel);
        if ( result.isHasErrors() )
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, result.getErrMsg());

        // 转换itemModel->dataobject
        ItemDO itemDO = convertItemDOFromItemModel(itemModel);

        // 写入数据库
        itemDOMapper.insertSelective(itemDO);

        itemModel.setId(itemDO.getId());

        ItemStockDO itemStockDO = convertItemStockDOFromItemModel(itemModel);
        itemStockDOMapper.insertSelective(itemStockDO);

        // 返回创建完成的对象
        return this.getItemById(itemModel.getId());
    }

    /**
     * ItemStaock: model->DO
     * @param itemModel
     * @return
     */
    private ItemStockDO convertItemStockDOFromItemModel(ItemModel itemModel){
        if (itemModel==null)
            return null;
        ItemStockDO itemStockDO = new ItemStockDO();
        itemStockDO.setItemId(itemModel.getId());
        itemStockDO.setStock(itemModel.getStock());
        return itemStockDO;
    }
    /**
     * Item : Model -> DO
     * @param itemModel
     * @return
     */
    private ItemDO convertItemDOFromItemModel(ItemModel itemModel){
        if (itemModel==null)
            return null;
        ItemDO itemDO = new ItemDO();
        BeanUtils.copyProperties(itemModel, itemDO);
        itemDO.setPrice(itemModel.getPrice().doubleValue());
        return itemDO;
    }

    /**
     * 返回所有商品的列表
     *
     * @return
     */
    @Override
    public List<ItemModel> listItems() {
        List<ItemDO> itemDOList = itemDOMapper.listItem();
        List<ItemModel> itemModelList = itemDOList.stream().map(itemDO -> {
            ItemStockDO itemStockDO = itemStockDOMapper.selectByItemId(itemDO.getId());
            ItemModel itemModel = convertModelFromDataObject(itemDO, itemStockDO);
            return itemModel;
        }).collect(Collectors.toList());
        return itemModelList;
    }

    /**
     * 根据商品id查找指定商品
     *
     * @param id
     * @return
     */
    @Override
    public ItemModel getItemById(Integer id) {
        ItemDO itemDO = itemDOMapper.selectByPrimaryKey(id);
        if (itemDO==null)
            return null;

        // 操作，获得库存数量
        ItemStockDO itemStockDO = itemStockDOMapper.selectByItemId(itemDO.getId());

        // 将dataobject->model
        ItemModel itemModel = convertModelFromDataObject(itemDO, itemStockDO);

        // 获取活动商品信息
        PromoModel promoModel = promoService.getPromoByItemId(itemModel.getId());

        if ( promoModel!=null && promoModel.getStatus().intValue()!=3 )
            itemModel.setPromoModel(promoModel);
        return itemModel;
    }

    /**
     * 库存扣减
     *
     * 到redis中减
     *
     * @param itemId
     * @return
     */
    @Override
    @Transactional
    public boolean decreaseStockInCache(Integer itemId, Integer amount) {
        //int affectedRow = itemStockDOMapper.decreaseStock(itemId, amount);
        // 在redis中减库存
        // result为剩余库存数
        long result = redisTemplate.opsForValue().increment("promo_item_stock_"+itemId, amount.intValue()*-1);
        if (result>0){
            // 更新成功
            return true;
        }else if (result==0){
            // 更新成功，且打上库存售罄的标示
            redisTemplate.opsForValue().set("promo_item_stock_invalid_"+itemId,"true");
            return true;
        }
        this.increaseStockInCache(itemId, amount); // 回滚
        return false;
    }

    /**
     * 异步扣减库存
     *
     * @param itemId
     * @param amount
     * @return
     */
    @Override
    public boolean asyncDecreaseStock(Integer itemId, Integer amount) {
        boolean mqResult = mqProducer.asyncReduceStock(itemId, amount);
        return mqResult;
    }

    /**
     * 回滚decreaseStockInCache
     *
     * @param itemId
     * @param amount
     * @return
     */
    @Override
    public boolean increaseStockInCache(Integer itemId, Integer amount) {
        long rest = redisTemplate.opsForValue().increment("promo_item_stock_"+itemId, amount.intValue());
        return rest>=0;
    }

    /**
     * itemDO + ItemStock -> model
     * @param itemDO
     * @param itemStockDO
     * @return
     */
    private ItemModel convertModelFromDataObject(ItemDO itemDO, ItemStockDO itemStockDO) {
        ItemModel itemModel = new ItemModel();
        BeanUtils.copyProperties(itemDO, itemModel);
        itemModel.setPrice(new BigDecimal(itemDO.getPrice()));
        itemModel.setStock(itemStockDO.getStock());
        return itemModel;
    }

    /**
     * 商品销量增加amount
     * @param itemId
     * @param amount
     */
    @Override
    @Transactional
    public int increaseSales(Integer itemId, Integer amount){
        return itemDOMapper.increaseSales(itemId, amount);
    }

    /**
     * 根据Id尝试从Redis中获取Item
     *
     * @param id
     * @return
     */
    @Override
    public ItemModel getItemByIdInCache(Integer id) {
        ItemModel itemModel = (ItemModel) redisTemplate.opsForValue().get("item_validate_"+id);
        if ( itemModel == null ){
            itemModel = this.getItemById(id);
            redisTemplate.opsForValue().set("item_validate_"+id, itemModel);
            redisTemplate.expire("item_validate_"+id, 10, TimeUnit.MINUTES);
        }
        return itemModel;
    }

    /**
     * 初始化库存流水
     *  @param itemId
     * @param amount
     * @return
     */
    @Override
    @Transactional
    public String initStockLog(Integer itemId, Integer amount) {
        StockLogDO stockLogDO = new StockLogDO();
        stockLogDO.setAmount(amount);
        stockLogDO.setItemId(itemId);
        // 使用UUID创建stock_log_id
        stockLogDO.setStockLogId(UUID.randomUUID().toString().replace("-",""));
        stockLogDO.setStatus(1);
        stockLogDOMapper.insertSelective(stockLogDO);
        return stockLogDO.getStockLogId();
    }
}
