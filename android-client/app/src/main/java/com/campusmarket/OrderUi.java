package com.campusmarket;

import android.content.Context;

final class OrderUi {
    private OrderUi(){}
    static String status(Context c,String state){int id;switch(state==null?"":state){case "PENDING_CONFIRMATION":id=R.string.order_pending_confirmation;break;case "PENDING_PAYMENT":id=R.string.order_pending_payment;break;case "PAID":id=R.string.order_paid;break;case "DELIVERED":id=R.string.order_delivered;break;case "COMPLETED":id=R.string.order_completed;break;case "CANCELLED":id=R.string.order_cancelled;break;default:id=R.string.order_unknown;}return c.getString(id);}
    static String payment(Context c,String state){return c.getString("SUCCEEDED".equals(state)?R.string.order_payment_ok:"REFUNDED".equals(state)?R.string.order_refunded:R.string.order_unpaid);}
    static int action(String action){switch(action){case "CONFIRM":return R.string.order_confirm;case "PAY":return R.string.order_pay;case "DELIVER":return R.string.order_deliver;case "RECEIVE":return R.string.order_receive;case "CANCEL":return R.string.order_cancel;default:return R.string.order_created;}}
    static int product(String status){return "ON_SALE".equals(status)?R.string.product_on_sale:"RESERVED".equals(status)?R.string.product_reserved:"SOLD".equals(status)?R.string.product_sold:R.string.product_off_shelf;}
}
