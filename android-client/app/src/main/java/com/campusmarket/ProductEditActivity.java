package com.campusmarket;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.View;
import android.widget.*;
import com.campusmarket.auth.SessionStore;
import com.campusmarket.network.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class ProductEditActivity extends Activity {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final CatalogApi api=new CatalogApi();private final ImageLoader loader=new ImageLoader();
    private final List<Long> images=new ArrayList<>();
    private SessionStore sessions;private CatalogApi.Category[] categories=new CatalogApi.Category[0];
    private boolean destroyed,busy,loaded;private long id;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);setContentView(R.layout.activity_product_edit);UiInsets.apply(findViewById(R.id.publish_root));sessions=new SessionStore(this);
        id=getIntent().getLongExtra("productId",0);((TextView)findViewById(R.id.publish_heading)).setText(id==0?R.string.publish_title:R.string.publish_edit);
        findViewById(R.id.publish_back).setOnClickListener(v->finish());findViewById(R.id.publish_save).setOnClickListener(v->save());
        findViewById(R.id.publish_retry).setOnClickListener(v->load());
        findViewById(R.id.publish_pick).setOnClickListener(v->{if(images.size()>=5){message(getString(R.string.product_image_limit));return;}Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("image/*");intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/jpeg","image/png"});startActivityForResult(intent,51);});
        if(state!=null){long[] ids=state.getLongArray("images");if(ids!=null)for(long n:ids)images.add(n);}
        load(state);
    }
    private void load(){load(null);}
    private void load(Bundle restored){String token=token();if(token==null||busy)return;busy(true);loaded=false;
        executor.execute(()->{try{CatalogApi.Category[] values=api.categories();CatalogApi.Product product=id==0||(restored!=null&&restored.getBoolean("loaded"))?null:api.own(token,id);
            handler.post(()->{if(destroyed)return;categories=values;List<String> labels=new ArrayList<>();for(CatalogApi.Category c:values)labels.add(c.name);
                ArrayAdapter<String> categoryAdapter=new ArrayAdapter<>(this,R.layout.market_spinner_item,labels);categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);((Spinner)findViewById(R.id.publish_category)).setAdapter(categoryAdapter);
                if(product!=null){set(R.id.publish_title,product.title);set(R.id.publish_price,product.price);set(R.id.publish_description,product.description);set(R.id.publish_condition,product.conditionLabel);set(R.id.publish_location,product.tradeLocation);images.clear();if(product.images!=null)for(CatalogApi.Image image:product.images)images.add(image.id);for(int i=0;i<values.length;i++)if(values[i].id==product.categoryId)((Spinner)findViewById(R.id.publish_category)).setSelection(i);}
                if(restored!=null)((Spinner)findViewById(R.id.publish_category)).setSelection(Math.min(restored.getInt("category",0),Math.max(0,values.length-1)));
                loaded=values.length>0;busy(false);renderImages();message(getString(R.string.product_ready));findViewById(R.id.publish_retry).setVisibility(View.GONE);
            });}catch(IOException e){error(e);}});
    }
    private String token(){String value=sessions.token();if(value==null){message(getString(R.string.product_sign_in));startActivity(new Intent(this,AuthActivity.class));finish();}return value;}
    private String value(int view){return ((EditText)findViewById(view)).getText().toString().trim();}
    private void set(int view,String value){((EditText)findViewById(view)).setText(value);}
    private void save(){if(busy||!loaded)return;String token=token();if(token==null)return;
        String title=value(R.id.publish_title),price=value(R.id.publish_price),description=value(R.id.publish_description),condition=value(R.id.publish_condition),location=value(R.id.publish_location);
        EditText titleField=findViewById(R.id.publish_title),priceField=findViewById(R.id.publish_price),descriptionField=findViewById(R.id.publish_description),conditionField=findViewById(R.id.publish_condition),locationField=findViewById(R.id.publish_location);
        for(EditText field:new EditText[]{titleField,priceField,descriptionField,conditionField,locationField})field.setError(null);
        boolean invalid=false;if(title.isEmpty()){titleField.setError(getString(R.string.field_required));invalid=true;}if(description.isEmpty()){descriptionField.setError(getString(R.string.field_required));invalid=true;}if(condition.isEmpty()){conditionField.setError(getString(R.string.field_required));invalid=true;}if(location.isEmpty()){locationField.setError(getString(R.string.field_required));invalid=true;}if(!price.matches("(?:0|[1-9][0-9]{0,7})(?:\\.[0-9]{1,2})?")){priceField.setError(getString(R.string.price_invalid));invalid=true;}if(invalid){message(getString(R.string.product_required));return;}
        int selected=((Spinner)findViewById(R.id.publish_category)).getSelectedItemPosition();if(selected<0||selected>=categories.length)return;
        Map<String,Object> draft=new LinkedHashMap<>();draft.put("title",title);draft.put("price",price);draft.put("description",description);draft.put("conditionLabel",condition);draft.put("tradeLocation",location);draft.put("categoryId",categories[selected].id);draft.put("imageIds",new ArrayList<>(images));busy(true);
        executor.execute(()->{try{api.save(token,id,draft);handler.post(()->{if(!destroyed){setResult(RESULT_OK);finish();}});}catch(IOException e){error(e);}});
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=51||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();String token=token();if(token==null||busy||images.size()>=5)return;busy(true);
        executor.execute(()->{try{String type=getContentResolver().getType(uri);if(!"image/png".equals(type)&&!"image/jpeg".equals(type))throw new IOException("Unsupported image");byte[] bytes;
            try(InputStream input=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){if(input==null)throw new IOException("Unreadable image");byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(out.size()+n>5242880)throw new IOException("Image too large");out.write(buffer,0,n);}bytes=out.toByteArray();}
            CatalogApi.Image image=new UserApi().upload(token,bytes,type);handler.post(()->{if(!destroyed){images.add(image.id);busy(false);renderImages();message(getString(R.string.product_ready));}});
        }catch(IOException|SecurityException e){error(e instanceof IOException?(IOException)e:new IOException("Cannot read image"));}});
    }
    private void renderImages(){LinearLayout container=findViewById(R.id.publish_images);container.removeAllViews();for(int i=0;i<images.size();i++){long image=images.get(i);ImageView view=new ImageView(this);int px=(int)(110*getResources().getDisplayMetrics().density),gap=(int)(12*getResources().getDisplayMetrics().density);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(px,px);params.setMarginEnd(gap);container.addView(view,params);view.setScaleType(ImageView.ScaleType.CENTER_CROP);view.setBackgroundResource(R.drawable.thumbnail_background);view.setClipToOutline(true);view.setContentDescription(getString(R.string.product_photo,i+1));loader.load(image,sessions.token(),view);view.setOnClickListener(v->{if(!busy){images.remove(Long.valueOf(image));renderImages();}});}}
    private void busy(boolean value){busy=value;for(int field:new int[]{R.id.publish_pick,R.id.publish_save,R.id.publish_category,R.id.publish_title,R.id.publish_price,R.id.publish_description,R.id.publish_condition,R.id.publish_location,R.id.publish_retry})findViewById(field).setEnabled(!value&&(loaded||field==R.id.publish_retry));if(value)message(getString(R.string.product_busy));}
    private void message(String text){((TextView)findViewById(R.id.publish_status)).setText(text);}
    private void error(IOException e){handler.post(()->{if(destroyed)return;busy(false);if(e instanceof UserApi.Failure&&((UserApi.Failure)e).status==401){sessions.clear();token();return;}message(e instanceof UserApi.Failure?e.getMessage():getString(R.string.request_network_error)+" / "+getString(R.string.product_image_invalid));findViewById(R.id.publish_retry).setVisibility(loaded?View.GONE:View.VISIBLE);});}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);long[] ids=new long[images.size()];for(int i=0;i<ids.length;i++)ids[i]=images.get(i);state.putLongArray("images",ids);state.putBoolean("loaded",loaded);state.putInt("category",((Spinner)findViewById(R.id.publish_category)).getSelectedItemPosition());}
    @Override protected void onDestroy(){destroyed=true;loader.close();executor.shutdownNow();handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
