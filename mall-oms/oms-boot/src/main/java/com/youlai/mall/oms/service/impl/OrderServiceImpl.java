package com.youlai.mall.oms.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.youlai.common.result.Result;
import com.youlai.common.web.exception.BizException;
import com.youlai.common.web.util.BeanMapperUtils;
import com.youlai.common.web.util.RequestUtils;
import com.youlai.mall.oms.enums.OrderStatusEnum;
import com.youlai.mall.oms.enums.OrderTypeEnum;
import com.youlai.mall.oms.mapper.OrderMapper;
import com.youlai.mall.oms.pojo.domain.OmsOrder;
import com.youlai.mall.oms.pojo.domain.OmsOrderItem;
import com.youlai.mall.oms.pojo.dto.OrderConfirmDTO;
import com.youlai.mall.oms.pojo.dto.OrderSubmitDTO;
import com.youlai.mall.oms.pojo.vo.*;
import com.youlai.mall.oms.service.ICartService;
import com.youlai.mall.oms.service.IOrderItemService;
import com.youlai.mall.oms.service.IOrderLogService;
import com.youlai.mall.oms.service.IOrderService;
import com.youlai.mall.pms.api.app.PmsSkuFeignService;
import com.youlai.mall.pms.pojo.domain.PmsSku;
import com.youlai.mall.pms.pojo.dto.SkuLockDTO;
import com.youlai.mall.ums.api.UmsAddressFeignService;
import com.youlai.mall.ums.pojo.domain.UmsAddress;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

import static com.youlai.mall.oms.constant.OmsConstants.*;

@AllArgsConstructor
@Slf4j
@Service
public class OrderServiceImpl extends ServiceImpl<OrderMapper, OmsOrder> implements IOrderService {

    private ICartService cartService;
    private PmsSkuFeignService skuFeignService;
    private UmsAddressFeignService addressFeignService;
    private IOrderItemService orderItemService;
    private IOrderLogService orderLogService;
    private RabbitTemplate rabbitTemplate;
    private StringRedisTemplate redisTemplate;
    private ThreadPoolExecutor threadPoolExecutor;

    /**
     * 订单确认
     */
    @Override
    public OrderConfirmVO confirm(OrderConfirmDTO orderConfirmDTO) {
        OrderConfirmVO orderConfirmVO = new OrderConfirmVO();
        // 获取购买商品信息
        CompletableFuture<Void> orderItemsCompletableFuture = CompletableFuture.runAsync(() -> {
            List<OrderItemVO> orderItems = new ArrayList<>();
            if (orderConfirmDTO.getSkuId() != null) { // 直接购买商品结算
                OrderItemVO orderItemVO = OrderItemVO.builder()
                        .skuId(orderConfirmDTO.getSkuId())
                        .count(orderConfirmDTO.getCount())
                        .build();
                PmsSku sku = skuFeignService.getSkuById(orderConfirmDTO.getSkuId()).getData();
                orderItemVO.setPrice(sku.getPrice());
                orderItemVO.setSkuPic(sku.getPic());
                orderItemVO.setTitle(sku.getTitle());
                orderItems.add(orderItemVO);
            } else { // 购物车中商品结算
                List<CartVO.CartItem> cartItems = cartService.getCartItems();
                List<OrderItemVO> items = cartItems.stream()
                        .filter(CartVO.CartItem::isChecked)
                        .map(cartItem -> OrderItemVO.builder()
                                .skuId(cartItem.getSkuId())
                                .count(cartItem.getCount())
                                .price(cartItem.getPrice())
                                .title(cartItem.getTitle())
                                .skuPic(cartItem.getPic())
                                .build())
                        .collect(Collectors.toList());
                orderItems.addAll(items);
            }
            orderConfirmVO.setOrderItems(orderItems);
        }, threadPoolExecutor);

        // 获取会员地址列表
        CompletableFuture<Void> addressesCompletableFuture = CompletableFuture.runAsync(() -> {
            List<UmsAddress> addresses = addressFeignService.list().getData();
            orderConfirmVO.setAddresses(addresses);
        }, threadPoolExecutor);


        // 生成唯一标识，防止订单重复提交
        CompletableFuture<Void> orderTokenCompletableFuture = CompletableFuture.runAsync(() -> {
            String orderToken = IdUtil.randomUUID();
            orderConfirmVO.setOrderToken(orderToken);
            redisTemplate.opsForValue().set(ORDER_TOKEN_PREFIX + orderToken, orderToken);
        }, threadPoolExecutor);

        CompletableFuture.allOf(orderItemsCompletableFuture, addressesCompletableFuture, orderTokenCompletableFuture);
        return orderConfirmVO;
    }

    /**
     * 订单提交
     */
    @Override
    @GlobalTransactional
    public OrderSubmitVO submit(OrderSubmitDTO submitDTO) {

        // 订单重复提交校验
        String orderToken = submitDTO.getOrderToken();
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(RELEASE_LOCK_LUA_SCRIPT, Long.class);
        Long result = this.redisTemplate.execute(redisScript, Collections.singletonList(ORDER_TOKEN_PREFIX + orderToken), orderToken);

        if (ObjectUtil.equals(result, RELEASE_LOCK_SUCCESS_RESULT)) {
            throw new BizException("订单不可重复提交");
        }

        List<OrderItemVO> orderItems = submitDTO.getOrderItems();
        if (CollectionUtil.isEmpty(orderItems)) {
            throw new BizException("请选择商品再提交");
        }

        // 订单验价
        Long currentTotalPrice = orderItems.stream().map(item -> {
            PmsSku sku = skuFeignService.getSkuById(item.getSkuId()).getData();
            if (sku != null) {
                return sku.getPrice() * item.getCount();
            }
            return 0l;
        }).reduce(0l, Long::sum);

        if (currentTotalPrice.compareTo(submitDTO.getTotalPrice()) != 0) {
            throw new BizException("页面已过期，请重新刷新页面再提交");
        }

        // 校验库存是否足够和锁库存
        List<SkuLockDTO> skuLockList = orderItems.stream()
                .map(item -> SkuLockDTO.builder().skuId(item.getSkuId())
                        .count(item.getCount())
                        .orderToken(orderToken)
                        .build())
                .collect(Collectors.toList());

        Result lockResult = skuFeignService.lockStock(skuLockList);

        if (!Result.success().getCode().equals(lockResult.getCode())) {
            throw new BizException(Result.failed().getMsg());
        }

        // 创建订单(状态：待支付)
        OmsOrder order = new OmsOrder();
        order.setOrderSn(orderToken) // 把orderToken赋值给订单编号【!】
                .setStatus(OrderStatusEnum.PENDING_PAYMENT.getCode())
                .setSourceType(OrderTypeEnum.APP.getCode())
                .setMemberId(RequestUtils.getUserId())
                .setRemark(submitDTO.getRemark());
        this.save(order);

        // 创建订单商品
        List<OmsOrderItem> orderItemList = orderItems.stream().map(item -> OmsOrderItem.builder()
                .orderId(order.getId())
                .skuId(item.getSkuId())
                .skuPrice(item.getPrice())
                .skuPic(item.getSkuPic())
                .skuQuantity(item.getCount())
                .build()).collect(Collectors.toList());
        orderItemService.saveBatch(orderItemList);

        // 将订单放入延时队列，超时未支付系统自动关单
        rabbitTemplate.convertAndSend("order.exchange", "order.create", orderToken);

        OrderSubmitVO submitVO = new OrderSubmitVO();
        submitVO.setId(order.getId());
        submitVO.setOrderSn(order.getOrderSn());
        return submitVO;
    }


    @Override
    public boolean closeOrder(String orderToken) {
        OmsOrder order = this.getOne(new LambdaQueryWrapper<OmsOrder>()
                .eq(OmsOrder::getOrderSn, orderToken));
        if (!OrderStatusEnum.PENDING_PAYMENT.getCode().equals(order.getStatus())) {
           throw new BizException("关单失败，订单状态不支持关闭");
        }
        order.setStatus(OrderStatusEnum.AUTO_CANCEL.getCode());
        return this.updateById(order);
    }

    @Override
    public boolean cancelOrder(Long id) {
        OmsOrder order = getByOrderId(id);
        if (!OrderStatusEnum.PENDING_PAYMENT.getCode().equals(order.getStatus())) {
            throw new BizException("取消失败，订单状态不支持取消");
        }
        order.setStatus(OrderStatusEnum.USER_CANCEL.getCode());
        return this.updateById(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteOrder(Long id) {
        // 查询订单，校验订单状态
        OmsOrder order = this.getByOrderId(id);
        if (!OrderStatusEnum.AUTO_CANCEL.getCode().equals(order.getStatus()) &&
                !OrderStatusEnum.USER_CANCEL.getCode().equals(order.getStatus())) {
            throw new BizException("删除失败，订单状态不允许删除");
        }
        return this.removeById(id);
    }

    @Override
    public List<OrderListVO> list(Integer status) {
        log.info("订单列表查询，status={}", status);
        QueryWrapper<OmsOrder> orderQuery = new QueryWrapper<>();
        if (status != 0) {
            orderQuery.eq("status", status);
        }
        orderQuery.orderByDesc("id");
        List<OmsOrder> orderList = this.list(orderQuery);
        if (orderList == null || orderList.size() <= 0) {
            log.info("订单列表查询结果为空，status={}", status);
            return null;
        }

        List<Long> orderIds = orderList.stream().map(order -> order.getId()).collect(Collectors.toList());
        Map<Long, List<OmsOrderItem>> orderItemsMap = orderItemService.getByOrderIds(orderIds);

        List<OrderListVO> result = orderList.stream().map(order -> {
            OrderListVO orderListVO = BeanMapperUtils.map(order, OrderListVO.class);
            orderListVO.setStatusDesc(OrderStatusEnum.getValue(orderListVO.getStatus()).getText());
            List<OmsOrderItem> orderItems = orderItemsMap.get(orderListVO.getId());
            if (CollectionUtil.isNotEmpty(orderItems)) {
                List<OrderListVO.OrderItemBean> orderItemBeans = orderItems.stream()
                        .map(orderItem -> BeanMapperUtils.map(orderItem, OrderListVO.OrderItemBean.class))
                        .collect(Collectors.toList());
                orderListVO.setOrderItemLIst(orderItemBeans);
            }
            return orderListVO;
        }).collect(Collectors.toList());
        return result;
    }

    @Override
    public OmsOrder getByOrderId(Long id) {
        Long userId = RequestUtils.getUserId();
        OmsOrder order = this.getOne(new LambdaQueryWrapper<OmsOrder>()
                .eq(OmsOrder::getId, id)
                .eq(OmsOrder::getMemberId, userId));
        if (order == null) {
            throw new BizException("订单不存在，订单ID非法");
        }
        return order;
    }
}
