package com.campusmarket;

import android.app.Activity;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.inputmethod.InputMethodManager;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import android.os.*;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.campusmarket.network.CatalogApi;
import com.campusmarket.network.UserApi;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

final class CatalogController {
    private final Activity activity;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final CatalogApi api=new CatalogApi();
    private final ImageLoader images=new ImageLoader();
    private final List<CatalogApi.Product> items=new ArrayList<>();
    private final ProductAdapter adapter=new ProductAdapter();
    private CatalogApi.Category[] categories=new CatalogApi.Category[0];
    private final EditText keyword;
    private final TextView status;
    private final Button more,retry;
    private int selectedCategory,selectedSort,page;
    private long total;
    private String appliedKeyword="";
    private volatile int generation;
    private boolean busy,closed,categoryLoaded;
    CatalogController(Activity activity){
        this.activity=activity;keyword=activity.findViewById(R.id.product_keyword);status=activity.findViewById(R.id.catalog_status);
        keyword.setImeHintLocales(LocaleList.forLanguageTags("zh-CN,en-US"));
        more=activity.findViewById(R.id.catalog_more);retry=activity.findViewById(R.id.catalog_retry);
        RecyclerView list=activity.findViewById(R.id.product_list);boolean landscape=activity.getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;list.setLayoutManager(landscape?new GridLayoutManager(activity,2):new LinearLayoutManager(activity));
        if(landscape){int gap=(int)(8*activity.getResources().getDisplayMetrics().density);list.addItemDecoration(new RecyclerView.ItemDecoration(){@Override public void getItemOffsets(Rect outRect,View view,RecyclerView parent,RecyclerView.State state){int position=parent.getChildAdapterPosition(view);outRect.left=position%2==0?0:gap;outRect.right=position%2==0?gap:0;}});}
        list.setAdapter(adapter);
        Spinner sort=activity.findViewById(R.id.product_sort);
        ArrayAdapter<String> sorts=new ArrayAdapter<>(activity,R.layout.market_spinner_item,activity.getResources().getStringArray(R.array.catalog_sorts));
        sorts.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);sort.setAdapter(sorts);
        sort.setOnItemSelectedListener(selection(position->{if(position!=selectedSort){selectedSort=position;load(true);}}));
        activity.findViewById(R.id.product_search).setOnClickListener(v->search());
        activity.findViewById(R.id.product_paste).setOnClickListener(v->{
            ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip=clipboard.getPrimaryClip();
            CharSequence text=clip==null||clip.getItemCount()==0?null:clip.getItemAt(0).getText();
            if(text==null||text.length()==0){Toast.makeText(activity,R.string.catalog_clipboard_empty,Toast.LENGTH_SHORT).show();return;}
            keyword.setText(text);keyword.setSelection(keyword.length());
        });
        keyword.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_SEARCH){search();return true;}return false;});
        more.setOnClickListener(v->{if(!busy)load(false);});retry.setOnClickListener(v->{if(categoryLoaded)load(true);else loadCategories();});
        loadCategories();
    }
    private void search(){
        ((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(keyword.getWindowToken(),0);
        keyword.clearFocus();load(true);
    }
    private interface Selection {void choose(int position);}
    private AdapterView.OnItemSelectedListener selection(Selection action){return new AdapterView.OnItemSelectedListener(){
        public void onItemSelected(AdapterView<?> parent,View view,int position,long id){action.choose(position);}
        public void onNothingSelected(AdapterView<?> parent){}
    };}
    private void loadCategories(){
        status.setText(R.string.catalog_loading);retry.setVisibility(View.GONE);
        executor.execute(()->{try{CatalogApi.Category[] values=api.categories();handler.post(()->{
            if(closed)return;categories=values;List<String> labels=new ArrayList<>();labels.add(activity.getString(R.string.catalog_all));
            for(CatalogApi.Category category:values)labels.add(category.name);
            Spinner spinner=activity.findViewById(R.id.product_category);
            ArrayAdapter<String> choices=new ArrayAdapter<>(activity,R.layout.market_spinner_item,labels);
            choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);spinner.setAdapter(choices);
            spinner.setOnItemSelectedListener(selection(position->{if(position!=selectedCategory){selectedCategory=position;load(true);}}));
            categoryLoaded=true;load(true);
        });}catch(IOException exception){error(exception, generation);}});
    }
    private void load(boolean reset){
        if(!categoryLoaded)return;
        int request=++generation;int target=reset?1:page+1;
        long category=selectedCategory==0?0:categories[selectedCategory-1].id;
        if(reset)appliedKeyword=keyword.getText().toString().trim();
        String query=appliedKeyword;String sort=new String[]{"latest","price_asc","price_desc"}[selectedSort];
        if(reset){int count=items.size();items.clear();adapter.notifyItemRangeRemoved(0,count);page=0;}
        busy=true;more.setEnabled(false);retry.setVisibility(View.GONE);status.setText(R.string.catalog_loading);
        executor.execute(()->{if(request!=generation)return;try{CatalogApi.Page result=api.list(target,category,query,sort);handler.post(()->{
            if(closed||request!=generation)return;busy=false;int start=items.size();Collections.addAll(items,result.items);adapter.notifyItemRangeInserted(start,result.items.length);
            page=result.page;total=result.total;status.setText(items.isEmpty()?activity.getString(R.string.catalog_empty):activity.getString(R.string.catalog_count,items.size(),total));
            more.setEnabled(true);more.setVisibility(items.size()<total?View.VISIBLE:View.GONE);
        });}catch(IOException exception){error(exception,request);}});
    }
    private void error(IOException exception,int request){handler.post(()->{
        if(closed||request!=generation)return;busy=false;status.setText(exception instanceof UserApi.Failure?exception.getMessage():activity.getString(R.string.request_network_error));
        retry.setVisibility(View.VISIBLE);more.setVisibility(View.GONE);
    });}
    void refresh(){if(categoryLoaded&&!closed)load(true);}
    void close(){closed=true;generation++;images.close();executor.shutdownNow();handler.removeCallbacksAndMessages(null);}
    private final class ProductAdapter extends RecyclerView.Adapter<ProductHolder>{
        public ProductHolder onCreateViewHolder(ViewGroup parent,int type){return new ProductHolder(LayoutInflater.from(activity).inflate(R.layout.item_product,parent,false));}
        public int getItemCount(){return items.size();}
        public void onBindViewHolder(ProductHolder holder,int position){CatalogApi.Product product=items.get(position);
            holder.title.setText(product.title);holder.price.setText(activity.getString(R.string.catalog_price,product.price));
            holder.meta.setText(activity.getString(R.string.catalog_meta,product.conditionLabel,product.tradeLocation));
            holder.badge.setText(activity.getString(R.string.catalog_placeholder,product.categoryName));
            holder.photo.setTag(null);boolean photo=product.images!=null&&product.images.length>0;holder.photo.setVisibility(photo?View.VISIBLE:View.GONE);if(photo)images.load(product.images[0].id,null,holder.photo);
            holder.itemView.setOnClickListener(v->activity.startActivity(new Intent(activity,ProductDetailActivity.class).putExtra("productId",product.id)));
        }
    }
    private static final class ProductHolder extends RecyclerView.ViewHolder{
        final TextView title,price,meta,badge;
        final ImageView photo;
        ProductHolder(View view){super(view);title=view.findViewById(R.id.product_title);price=view.findViewById(R.id.product_price);meta=view.findViewById(R.id.product_meta);badge=view.findViewById(R.id.product_badge);photo=view.findViewById(R.id.product_photo);((View)photo.getParent()).setClipToOutline(true);}
    }
}
