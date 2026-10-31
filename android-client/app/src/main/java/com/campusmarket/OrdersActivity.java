package com.campusmarket;

import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import androidx.recyclerview.widget.*;
import com.campusmarket.auth.SessionStore;
import com.campusmarket.network.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public final class OrdersActivity extends Activity {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();private final Handler handler=new Handler(Looper.getMainLooper());
    private final OrderApi api=new OrderApi();private final List<OrderApi.Order> items=new ArrayList<>();private final Adapter adapter=new Adapter();
    private SessionStore sessions;private String role;private boolean destroyed,busy;private int page;
    @Override public void onCreate(Bundle state){super.onCreate(state);setContentView(R.layout.activity_orders);UiInsets.apply(findViewById(R.id.orders_root));sessions=new SessionStore(this);
        role=getIntent().getBooleanExtra("seller",false)?"seller":"buyer";((TextView)findViewById(R.id.orders_heading)).setText(role.equals("seller")?R.string.orders_seller:R.string.orders_buyer);
        RecyclerView list=findViewById(R.id.orders_list);list.setLayoutManager(new LinearLayoutManager(this));list.setAdapter(adapter);
        findViewById(R.id.orders_back).setOnClickListener(v->finish());findViewById(R.id.orders_refresh).setOnClickListener(v->load(true));findViewById(R.id.orders_more).setOnClickListener(v->load(false));}
    @Override protected void onResume(){super.onResume();load(true);}
    private String token(){String value=sessions.token();if(value==null){startActivity(new Intent(this,AuthActivity.class));finish();}return value;}
    private void load(boolean reset){if(busy)return;String token=token();if(token==null)return;busy(true);message(getString(R.string.catalog_loading));int target=reset?1:page+1;
        executor.execute(()->{try{OrderApi.Page result=api.list(token,role,target);handler.post(()->{if(destroyed)return;busy(false);if(reset){int count=items.size();items.clear();adapter.notifyItemRangeRemoved(0,count);}int start=items.size();Collections.addAll(items,result.items);adapter.notifyItemRangeInserted(start,result.items.length);page=result.page;message(items.isEmpty()?getString(R.string.catalog_empty):getString(R.string.catalog_count,items.size(),result.total));findViewById(R.id.orders_more).setVisibility(items.size()<result.total?View.VISIBLE:View.GONE);});}catch(IOException e){handler.post(()->{if(destroyed)return;busy(false);if(e instanceof UserApi.Failure&&((UserApi.Failure)e).status==401){sessions.clear();token();return;}message(e instanceof UserApi.Failure?e.getMessage():getString(R.string.request_network_error));});}});
    }
    private void busy(boolean value){busy=value;findViewById(R.id.orders_refresh).setEnabled(!value);findViewById(R.id.orders_more).setEnabled(!value);}
    private void message(String text){((TextView)findViewById(R.id.orders_status)).setText(text);}
    private final class Adapter extends RecyclerView.Adapter<Holder>{
        public Holder onCreateViewHolder(ViewGroup parent,int type){return new Holder(LayoutInflater.from(OrdersActivity.this).inflate(R.layout.item_order,parent,false));}
        public int getItemCount(){return items.size();}
        public void onBindViewHolder(Holder h,int position){OrderApi.Order o=items.get(position);h.title.setText(o.title);h.price.setText(getString(R.string.order_amount,o.amount));h.status.setText(OrderUi.status(OrdersActivity.this,o.status));h.id.setText(getString(R.string.order_number,o.id));h.meta.setText(getString(R.string.order_list_details,OrderUi.payment(OrdersActivity.this,o.paymentStatus),o.tradeLocation,o.updatedAt));h.itemView.setOnClickListener(v->startActivity(new Intent(OrdersActivity.this,OrderDetailActivity.class).putExtra("orderId",o.id)));}
    }
    private static final class Holder extends RecyclerView.ViewHolder {final TextView title,price,meta,status,id;Holder(View view){super(view);title=view.findViewById(R.id.order_card_title);price=view.findViewById(R.id.order_card_price);meta=view.findViewById(R.id.order_card_meta);status=view.findViewById(R.id.order_card_status);id=view.findViewById(R.id.order_card_id);}}
    @Override protected void onDestroy(){destroyed=true;executor.shutdownNow();handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
