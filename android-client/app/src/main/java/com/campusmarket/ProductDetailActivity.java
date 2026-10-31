package com.campusmarket;

import android.app.Activity;
import android.os.*;
import android.view.View;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.content.Intent;
import com.campusmarket.auth.SessionStore;
import com.campusmarket.network.*;
import java.io.IOException;
import java.util.concurrent.*;

public final class ProductDetailActivity extends Activity {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean destroyed,busy;
    private long id;
    private final ImageLoader images=new ImageLoader();
    private boolean favorite,favoriteBusy,favoriteLoaded;
    private boolean orderDialogOpen;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);setContentView(R.layout.activity_product_detail);UiInsets.apply(findViewById(R.id.detail_root));
        id=getIntent().getLongExtra("productId",0);if(id<=0){finish();return;}
        findViewById(R.id.detail_favorite).setOnClickListener(v->changeFavorite());
        findViewById(R.id.detail_contact).setOnClickListener(v->startActivity(new Intent(this,new SessionStore(this).token()==null?AuthActivity.class:ChatActivity.class).putExtra("productId",id)));
        findViewById(R.id.detail_buy).setOnClickListener(v->{if(orderDialogOpen)return;if(new SessionStore(this).token()==null){startActivity(new Intent(this,AuthActivity.class));return;}
            orderDialogOpen=true;android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setMessage(R.string.order_buy_confirm).setNegativeButton(R.string.product_cancel,null).setPositiveButton(R.string.order_buy,(d,w)->{findViewById(R.id.detail_buy).setEnabled(false);startActivity(new Intent(this,OrderDetailActivity.class).putExtra("productId",id).putExtra("requestId",java.util.UUID.randomUUID().toString()));}).create();dialog.setOnDismissListener(d->orderDialogOpen=false);dialog.show();});
        findViewById(R.id.detail_back).setOnClickListener(v->finish());findViewById(R.id.detail_retry).setOnClickListener(v->load());load();
    }
    private void load(){if(busy)return;busy=true;findViewById(R.id.detail_buy).setEnabled(false);text(R.id.detail_status,getString(R.string.catalog_loading));findViewById(R.id.detail_retry).setVisibility(View.GONE);
        executor.execute(()->{try{CatalogApi.Product product=new CatalogApi().detail(id);handler.post(()->{
            if(destroyed)return;busy=false;text(R.id.detail_status,"");text(R.id.detail_title,product.title);text(R.id.detail_price,getString(R.string.catalog_price,product.price));
            text(R.id.detail_meta,getString(R.string.catalog_detail_meta,product.categoryName,product.conditionLabel,product.tradeLocation,product.createdAt));
            text(R.id.detail_description,product.description);text(R.id.detail_seller,getString(R.string.catalog_seller,product.sellerNickname,filled(product.sellerSchool),filled(product.sellerCampus)));
            findViewById(R.id.detail_content).setVisibility(View.VISIBLE);
            findViewById(R.id.detail_buy).setEnabled(true);
            findViewById(R.id.detail_contact).setEnabled(true);
            LinearLayout gallery=findViewById(R.id.detail_images);gallery.removeAllViews();
            boolean hasImages=product.images!=null&&product.images.length>0;findViewById(R.id.detail_no_photo).setVisibility(hasImages?View.GONE:View.VISIBLE);
            if(hasImages)for(CatalogApi.Image image:product.images){ImageView view=new ImageView(this);int gap=(int)(12*getResources().getDisplayMetrics().density);int px=getResources().getDisplayMetrics().widthPixels-(int)(32*getResources().getDisplayMetrics().density);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(px,px);params.setMarginEnd(gap);gallery.addView(view,params);view.setScaleType(ImageView.ScaleType.CENTER_CROP);view.setBackgroundResource(R.drawable.thumbnail_background);view.setClipToOutline(true);view.setContentDescription(getString(R.string.product_public_photo));images.load(image.id,null,view);}
            loadFavorite();
        });}catch(IOException exception){handler.post(()->{if(destroyed)return;busy=false;findViewById(R.id.detail_content).setVisibility(View.GONE);text(R.id.detail_status,exception instanceof UserApi.Failure?exception.getMessage():getString(R.string.request_network_error));findViewById(R.id.detail_retry).setVisibility(View.VISIBLE);});}});
    }
    @Override protected void onResume(){super.onResume();if(id>0)load();}
    private void loadFavorite(){String token=new SessionStore(this).token();favoriteLoaded=false;if(token==null){text(R.id.detail_favorite,getString(R.string.product_favorite));return;}if(favoriteBusy)return;favoriteBusy=true;
        executor.execute(()->{try{boolean value=new CatalogApi().favorite(token,id,null);handler.post(()->{if(!destroyed){favoriteBusy=false;favoriteLoaded=true;favorite=value;text(R.id.detail_favorite,getString(value?R.string.product_unfavorite:R.string.product_favorite));}});}catch(IOException e){handler.post(()->{if(!destroyed){favoriteBusy=false;favoriteError(e);}});}});
    }
    private void changeFavorite(){SessionStore store=new SessionStore(this);String token=store.token();if(token==null){startActivity(new Intent(this,AuthActivity.class));return;}if(favoriteBusy)return;if(!favoriteLoaded){loadFavorite();return;}favoriteBusy=true;
        executor.execute(()->{try{boolean value=new CatalogApi().favorite(token,id,!favorite);handler.post(()->{if(!destroyed){favoriteBusy=false;favorite=value;text(R.id.detail_favorite,getString(value?R.string.product_unfavorite:R.string.product_favorite));}});}catch(IOException e){handler.post(()->{if(!destroyed){favoriteBusy=false;favoriteError(e);}});}});
    }
    private void favoriteError(IOException e){if(e instanceof UserApi.Failure&&((UserApi.Failure)e).status==401)new SessionStore(this).clear();text(R.id.detail_status,e instanceof UserApi.Failure?e.getMessage():getString(R.string.request_network_error));}
    private String filled(String text){return text==null||text.isEmpty()?getString(R.string.not_filled):text;}
    private void text(int view,String value){((TextView)findViewById(view)).setText(value);}
    @Override protected void onDestroy(){destroyed=true;images.close();executor.shutdownNow();handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
