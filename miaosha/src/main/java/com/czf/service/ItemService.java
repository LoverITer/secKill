package com.czf.service;

import com.czf.error.BusinessException;
import com.czf.service.model.ItemModel;

import java.util.List;

/**
 * @author czf
 * @Date 2020/3/10 11:12 下午
 */
public interface ItemService {
    /**
     * 创建商品
     * @param itemModel
     * @return
     */
    ItemModel createItem(ItemModel itemModel) throws BusinessException;

    /**
     * 返回所有商品的列表
     * @return
     */
    List<ItemModel> listItems();

    /**
     * 根据商品id查找指定商品
     * @param id
     * @return
     */
    ItemModel getItemById(Integer id);

    /**
     * 库存扣减
     * @param itemId
     * @return
     */
    boolean decreaseStock(Integer itemId, Integer amount) throws BusinessException;

    /**
     * 销量增加
     * @param itemId
     * @param amount
     * @return
     */
    int increaseSales(Integer itemId, Integer amount);

    /**
     * 根据Id尝试从Redis中获取Item
     * @param id
     * @return
     */
    ItemModel getItemByIdInCache(Integer id);

}
