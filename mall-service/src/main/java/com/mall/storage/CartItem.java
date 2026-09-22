package com.mall.storage;

/**
 * 购物车中的一行 —— 只承载「用户意图」，不承载「商品事实」。
 *
 * <h3>为什么没有 name / price / stock / coverUrl</h3>
 *
 * <p>购物车里存的应该是<b>用户想买什么</b>：商品 ID、数量、勾没勾选、什么时候加的。
 * 而 <b>价格、库存、是否上架</b>是「商品事实」，它们随时会变，必须每次实时回源
 * {@code mall_product} 去取。</p>
 *
 * <p>一旦把价格快照塞进购物车，就会出现这类必然的不一致：用户三天前加购时是 99 元，
 * 现在商品已经降到 79 元 —— 购物车却还显示 99，结算时又变 79，用户会认为系统在骗人。
 * 反过来若商品涨价，快照价又会让平台亏钱。<b>事实不进车，只进商品表。</b></p>
 *
 * <p>同理，"商品下架/库存为 0"也不该从这里消失（不能静默删除条目），而应由上层
 * 拼装 VO 时打上"失效"标记，把去留交给用户。</p>
 *
 * @param productId 商品 ID
 * @param quantity  数量
 * @param selected  结算是否勾选
 * @param addTime   首次加购时间（毫秒时间戳），用于列表按加购顺序排列
 */
public record CartItem(Long productId, int quantity, boolean selected, long addTime) {
}
