package com.campusmarket;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.campusmarket.auth.SessionStore;
import com.campusmarket.network.*;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.*;

public final class OrderDetailActivity extends Activity {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();private final Handler handler=new Handler(Looper.getMainLooper());private final OrderApi api=new OrderApi();
    private SessionStore sessions;private boolean destroyed,busy;private long id,product;private String requestId;private OrderApi.Order order;
    @Override public void onCreate(Bundle state){super.onCreate(state);setContentView(R.layout.activity_order_detail);UiInsets.apply(findViewById(R.id.order_root));sessions=new SessionStore(this);
        id=state==null?getIntent().getLongExtra("orderId",0):state.getLong("orderId");product=getIntent().getLongExtra("productId",0);
        requestId=getIntent().getStringExtra("requestId");if(requestId==null){requestId=UUID.randomUUID().toString();getIntent().putExtra("requestId",requestId);}
        findViewById(R.id.order_back).setOnClickListener(v->finish());findViewById(R.id.order_refresh).setOnClickListener(v->load());if(id<=0&&product<=0){finish();return;}}
    @Override protected void onResume(){super.onResume();load();}
    private String token(){String value=sessions.token();if(value==null){startActivity(new Intent(this,AuthActivity.class));finish();}return value;}
    private void load(){if(busy)return;String token=token();if(token==null)return;busy(true);message(getString(R.string.catalog_loading));long current=id;
        executor.execute(()->{try{OrderApi.Order result=current>0?api.detail(token,current):api.create(token,product,requestId);handler.post(()->{if(!destroyed){busy(false);render(result);}});}catch(IOException e){error(e);}});
    }
    private void render(OrderApi.Order value){order=value;id=value.id;getIntent().putExtra("orderId",id);message("");findViewById(R.id.order_content).setVisibility(View.VISIBLE);
        ((TextView)findViewById(R.id.order_state)).setText(OrderUi.status(this,value.status));((TextView)findViewById(R.id.order_product_title)).setText(value.title);((TextView)findViewById(R.id.order_amount)).setText(getString(R.string.order_amount,value.amount));
        ((TextView)findViewById(R.id.order_details)).setText(getString(R.string.order_summary,OrderUi.payment(this,value.paymentStatus),value.buyerNickname,value.sellerNickname,value.tradeLocation,value.conditionLabel,value.createdAt,value.updatedAt));((TextView)findViewById(R.id.order_description)).setText(value.description);
        LinearLayout actions=findViewById(R.id.order_actions);actions.removeAllViews();if(value.allowedActions!=null)for(String action:value.allowedActions){int style="CANCEL".equals(action)?R.style.DangerButton:R.style.ActionButton;Button button=new Button(this,null,0,style);button.setText(OrderUi.action(action));actions.addView(button);button.setOnClickListener(v->confirm(action));}
        StringBuilder history=new StringBuilder();if(value.events!=null)for(OrderApi.Event event:value.events)history.append("● ").append(getString(OrderUi.action(event.action))).append('\n').append("   ").append(event.createdAt).append("\n\n");((TextView)findViewById(R.id.order_events)).setText(history.toString().trim());
    }
    private void confirm(String action){if(busy||order==null)return;String prompt;
        switch(action){case "PAY":prompt=getString(R.string.order_pay_confirm,order.amount);break;case "CANCEL":prompt=getString(R.string.order_cancel_confirm);break;case "DELIVER":prompt=getString(R.string.order_deliver_confirm);break;case "RECEIVE":prompt=getString(R.string.order_receive_confirm);break;default:prompt=getString(R.string.order_action_confirm,getString(OrderUi.action(action)));}
        new AlertDialog.Builder(this).setMessage(prompt).setNegativeButton(R.string.product_cancel,null).setPositiveButton(OrderUi.action(action),(d,w)->act(action)).show();
    }
    private void act(String action){if(busy)return;String token=token();if(token==null)return;busy(true);message(getString(R.string.product_busy));executor.execute(()->{try{OrderApi.Order value=api.act(token,id,action);handler.post(()->{if(!destroyed){busy(false);render(value);}});}catch(IOException e){error(e);}});}
    private void busy(boolean value){busy=value;findViewById(R.id.order_refresh).setEnabled(!value);LinearLayout actions=findViewById(R.id.order_actions);for(int i=0;i<actions.getChildCount();i++)actions.getChildAt(i).setEnabled(!value);}
    private void message(String text){((TextView)findViewById(R.id.order_status)).setText(text);}
    private void error(IOException e){handler.post(()->{if(destroyed)return;busy(false);((LinearLayout)findViewById(R.id.order_actions)).removeAllViews();if(e instanceof UserApi.Failure&&((UserApi.Failure)e).status==401){sessions.clear();token();return;}message(e instanceof UserApi.Failure?e.getMessage():getString(R.string.order_retry_hint));});}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putLong("orderId",id);}
    @Override protected void onDestroy(){destroyed=true;executor.shutdownNow();handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
