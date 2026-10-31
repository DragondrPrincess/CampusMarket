package com.campusmarket;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import com.campusmarket.auth.SessionStore;
import com.campusmarket.network.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public final class MyProductsActivity extends Activity {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();private final Handler handler=new Handler(Looper.getMainLooper());
    private final CatalogApi api=new CatalogApi();private final List<CatalogApi.Product> items=new ArrayList<>();private final Adapter adapter=new Adapter();
    private final ImageLoader images=new ImageLoader();private SessionStore sessions;private boolean favorites,busy,destroyed;private int page;
    @Override public void onCreate(Bundle state){super.onCreate(state);setContentView(R.layout.activity_my_products);UiInsets.apply(findViewById(R.id.mine_root));sessions=new SessionStore(this);favorites=getIntent().getBooleanExtra("favorites",false);
        ((TextView)findViewById(R.id.mine_heading)).setText(favorites?R.string.my_favorites:R.string.my_products);RecyclerView list=findViewById(R.id.mine_list);list.setLayoutManager(new LinearLayoutManager(this));list.setAdapter(adapter);
        findViewById(R.id.mine_back).setOnClickListener(v->finish());findViewById(R.id.mine_refresh).setOnClickListener(v->load(true));findViewById(R.id.mine_more).setOnClickListener(v->load(false));}
    @Override protected void onResume(){super.onResume();load(true);}
    private String token(){String token=sessions.token();if(token==null){startActivity(new Intent(this,AuthActivity.class));finish();}return token;}
    private void load(boolean reset){if(busy)return;String token=token();if(token==null)return;busy=true;message(getString(R.string.catalog_loading));int target=reset?1:page+1;
        executor.execute(()->{try{CatalogApi.Page result=api.mine(token,target,favorites);handler.post(()->{if(destroyed)return;busy=false;if(reset){int count=items.size();items.clear();adapter.notifyItemRangeRemoved(0,count);}int start=items.size();Collections.addAll(items,result.items);adapter.notifyItemRangeInserted(start,result.items.length);page=result.page;message(items.isEmpty()?getString(R.string.catalog_empty):getString(R.string.catalog_count,items.size(),result.total));findViewById(R.id.mine_more).setVisibility(items.size()<result.total?View.VISIBLE:View.GONE);});}catch(IOException e){error(e);}});
    }
    private void manage(CatalogApi.Product product){if(busy)return;if(favorites){startActivity(new Intent(this,ProductDetailActivity.class).putExtra("productId",product.id));return;}
        if(!"ON_SALE".equals(product.status)&&!"OFF_SHELF".equals(product.status)){new AlertDialog.Builder(this).setMessage(R.string.order_manage_reserved).setNegativeButton(R.string.product_cancel,null).setPositiveButton(R.string.orders_seller,(d,w)->startActivity(new Intent(this,OrdersActivity.class).putExtra("seller",true))).show();return;}String[] choices={getString(R.string.publish_edit),getString("ON_SALE".equals(product.status)?R.string.product_take_down:R.string.product_relist),getString(R.string.product_delete)};
        new AlertDialog.Builder(this).setTitle(R.string.product_manage).setItems(choices,(d,which)->{
            if(which==0)startActivity(new Intent(this,ProductEditActivity.class).putExtra("productId",product.id));
            else if(which==2)new AlertDialog.Builder(this).setMessage(R.string.product_delete_confirm).setNegativeButton(R.string.product_cancel,null).setPositiveButton(R.string.product_delete,(dialog,w)->mutate(product,true)).show();
            else mutate(product,false);
        }).show();
    }
    private void mutate(CatalogApi.Product product,boolean delete){if(busy)return;String token=token();if(token==null)return;busy=true;message(getString(R.string.product_busy));executor.execute(()->{try{if(delete)api.delete(token,product.id);else api.status(token,product.id,"ON_SALE".equals(product.status)?"OFF_SHELF":"ON_SALE");handler.post(()->{if(!destroyed){busy=false;load(true);}});}catch(IOException e){error(e);}});}
    private void error(IOException e){handler.post(()->{if(destroyed)return;busy=false;if(e instanceof UserApi.Failure&&((UserApi.Failure)e).status==401){sessions.clear();token();return;}message(e instanceof UserApi.Failure?e.getMessage():getString(R.string.request_network_error));});}
    private void message(String text){((TextView)findViewById(R.id.mine_status)).setText(text);}
    private final class Adapter extends RecyclerView.Adapter<Holder>{
        public Holder onCreateViewHolder(ViewGroup parent,int type){return new Holder(LayoutInflater.from(MyProductsActivity.this).inflate(R.layout.item_product,parent,false));}
        public int getItemCount(){return items.size();}
        public void onBindViewHolder(Holder h,int position){CatalogApi.Product p=items.get(position);h.title.setText(p.title);h.price.setText(getString(R.string.catalog_price,p.price));h.badge.setText(getString(R.string.catalog_placeholder,p.categoryName));h.meta.setText(favorites?p.tradeLocation:getString(R.string.product_manage_hint,getString(OrderUi.product(p.status)),p.tradeLocation));h.photo.setTag(null);boolean photo=p.images!=null&&p.images.length>0;h.photo.setVisibility(photo?View.VISIBLE:View.GONE);if(photo)images.load(p.images[0].id,sessions.token(),h.photo);h.itemView.setOnClickListener(v->manage(p));}
    }
    private static final class Holder extends RecyclerView.ViewHolder {final TextView title,price,meta,badge;final ImageView photo;Holder(View view){super(view);title=view.findViewById(R.id.product_title);price=view.findViewById(R.id.product_price);meta=view.findViewById(R.id.product_meta);badge=view.findViewById(R.id.product_badge);photo=view.findViewById(R.id.product_photo);((View)photo.getParent()).setClipToOutline(true);}}
    @Override protected void onDestroy(){destroyed=true;images.close();executor.shutdownNow();handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
