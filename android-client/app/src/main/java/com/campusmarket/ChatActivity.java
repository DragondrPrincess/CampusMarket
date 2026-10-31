package com.campusmarket;

import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import com.campusmarket.auth.SessionStore;
import com.campusmarket.network.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public final class ChatActivity extends Activity {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();private final Handler handler=new Handler(Looper.getMainLooper());
    private final MessagingApi api=new MessagingApi();private final List<MessagingApi.Message> items=new ArrayList<>();private final Adapter adapter=new Adapter();
    private SessionStore sessions;private RecyclerView list;private EditText input;private long id,product,acknowledged;private String pendingId,pendingText;
    private boolean destroyed,busy,loaded,ackBusy;private volatile boolean active;
    private final Runnable poll=new Runnable(){public void run(){if(!active)return;load(false,false);handler.postDelayed(this,3000);}};
    @Override public void onCreate(Bundle state){super.onCreate(state);setContentView(R.layout.activity_chat);UiInsets.apply(findViewById(R.id.chat_root));sessions=new SessionStore(this);
        id=state==null?getIntent().getLongExtra("conversationId",0):state.getLong("conversationId");product=getIntent().getLongExtra("productId",0);
        if(state!=null){pendingId=state.getString("pendingId");pendingText=state.getString("pendingText");}
        input=findViewById(R.id.chat_input);list=findViewById(R.id.chat_list);list.setLayoutManager(new LinearLayoutManager(this));list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener(){@Override public void onScrollStateChanged(RecyclerView view,int state){if(state==RecyclerView.SCROLL_STATE_IDLE)ackVisible();}});
        findViewById(R.id.chat_back).setOnClickListener(v->finish());findViewById(R.id.chat_refresh).setOnClickListener(v->load(false,true));findViewById(R.id.chat_older).setOnClickListener(v->load(true,false));findViewById(R.id.chat_send).setOnClickListener(v->send());
        if(id<=0&&product<=0){finish();return;}controls();
    }
    @Override protected void onResume(){super.onResume();active=true;load(false,true);handler.postDelayed(poll,3000);}
    @Override protected void onPause(){active=false;handler.removeCallbacks(poll);super.onPause();}
    private String token(){String value=sessions.token();if(value==null){startActivity(new Intent(this,AuthActivity.class));finish();}return value;}
    private void load(boolean older,boolean forceBottom){if(busy)return;String token=token();if(token==null)return;
        Long before=older&&!items.isEmpty()?items.get(0).seq:null,after=!older&&loaded&&!items.isEmpty()?items.get(items.size()-1).seq:null;
        boolean atBottom=!list.canScrollVertically(1);busy=true;controls();if(!loaded)message(getString(R.string.catalog_loading));
        executor.execute(()->{try{
            long conversation=id;if(conversation==0)conversation=api.open(token,product).id;
            MessagingApi.Messages value=api.messages(token,conversation,after,before);
            handler.post(()->{if(destroyed)return;id=value.conversation.id;getIntent().putExtra("conversationId",id);loaded=true;busy=false;
                ((TextView)findViewById(R.id.chat_heading)).setText(getString(R.string.chat_header,value.conversation.peerNickname,value.conversation.productTitle));
                ((TextView)findViewById(R.id.chat_product_note)).setText(getString(R.string.chat_product_note,getString(OrderUi.product(value.conversation.productStatus))));
                acknowledged=Math.max(acknowledged,value.conversation.readSeq);
                if(older){List<MessagingApi.Message> prefix=new ArrayList<>();for(MessagingApi.Message m:value.items)if(items.isEmpty()||m.seq<items.get(0).seq)prefix.add(m);items.addAll(0,prefix);adapter.notifyItemRangeInserted(0,prefix.size());}
                else{int start=items.size();for(MessagingApi.Message m:value.items){if(items.isEmpty()||m.seq>items.get(items.size()-1).seq)items.add(m);reconcile(m);}adapter.notifyItemRangeInserted(start,items.size()-start);}
                controls();message(pendingId!=null?getString(R.string.message_send_failed):items.isEmpty()?getString(R.string.chat_empty):getString(R.string.message_loaded,items.size()));
                findViewById(R.id.chat_older).setVisibility(!items.isEmpty()&&items.get(0).seq>1?View.VISIBLE:View.GONE);
                if(!older&&(forceBottom||atBottom)&&!items.isEmpty())list.scrollToPosition(items.size()-1);
                list.post(()->{if(!destroyed)ackVisible();});
                if(!older&&value.hasMoreAfter&&active)load(false,forceBottom);
            });
        }catch(IOException e){failure(e,false);}});
    }
    private void send(){if(busy||!loaded)return;String token=token();if(token==null)return;
        if(pendingId==null){String text=input.getText().toString().trim();if(text.isEmpty()||text.codePointCount(0,text.length())>1000){message(getString(R.string.message_invalid));return;}pendingText=text;pendingId=UUID.randomUUID().toString();}
        String key=pendingId,text=pendingText;busy=true;controls();message(getString(R.string.message_sending));
        executor.execute(()->{try{api.send(token,id,key,text);handler.post(()->{if(destroyed)return;pendingId=null;pendingText=null;input.setText("");busy=false;controls();load(false,true);});}catch(IOException e){failure(e,true);}});
    }
    private void reconcile(MessagingApi.Message m){if(m.mine&&pendingId!=null&&pendingId.equals(m.requestId)){pendingId=null;pendingText=null;input.setText("");}}
    private void ackVisible(){if(!active||ackBusy||items.isEmpty()||list.canScrollVertically(1))return;long through=items.get(items.size()-1).seq;if(through<=acknowledged)return;String token=sessions.token();if(token==null)return;ackBusy=true;
        executor.execute(()->{try{if(!active){handler.post(()->ackBusy=false);return;}MessagingApi.Conversation c=api.read(token,id,through);handler.post(()->{if(!destroyed){ackBusy=false;acknowledged=Math.max(acknowledged,c.readSeq);}});}catch(IOException e){handler.post(()->{if(!destroyed){ackBusy=false;if(!busy&&pendingId==null)message(getString(R.string.chat_read_failed));}});}});
    }
    private void controls(){findViewById(R.id.chat_send).setEnabled(loaded&&!busy);((Button)findViewById(R.id.chat_send)).setText(pendingId==null?R.string.message_send:R.string.message_retry_send);input.setEnabled(pendingId==null);findViewById(R.id.chat_refresh).setEnabled(!busy);findViewById(R.id.chat_older).setEnabled(!busy);}
    private void failure(IOException e,boolean sending){handler.post(()->{if(destroyed)return;busy=false;if(e instanceof UserApi.Failure&&((UserApi.Failure)e).status==401){sessions.clear();token();return;}
        if(sending&&e instanceof UserApi.Failure&&((UserApi.Failure)e).status==400){pendingId=null;pendingText=null;}
        controls();message(e instanceof UserApi.Failure?e.getMessage():sending?getString(R.string.message_send_failed):getString(R.string.request_network_error));});}
    private void message(String text){((TextView)findViewById(R.id.chat_status)).setText(text);}
    private final class Adapter extends RecyclerView.Adapter<Holder>{
        public Holder onCreateViewHolder(ViewGroup parent,int type){return new Holder(LayoutInflater.from(ChatActivity.this).inflate(R.layout.item_message,parent,false));}
        public int getItemCount(){return items.size();}
        public void onBindViewHolder(Holder h,int position){MessagingApi.Message m=items.get(position);h.content.setText(m.content);h.time.setText(getString(R.string.message_time,getString(m.mine?R.string.message_me:R.string.message_peer),m.createdAt));h.row.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(m.mine?R.color.market_accent_soft:R.color.market_surface)));FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)h.row.getLayoutParams();params.gravity=m.mine?Gravity.END:Gravity.START;h.row.setLayoutParams(params);}
    }
    private static final class Holder extends RecyclerView.ViewHolder {final TextView content,time;final View row;Holder(View v){super(v);content=v.findViewById(R.id.message_content);time=v.findViewById(R.id.message_time);row=v.findViewById(R.id.message_row);}}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putLong("conversationId",id);state.putString("pendingId",pendingId);state.putString("pendingText",pendingText);}
    @Override protected void onDestroy(){destroyed=true;executor.shutdownNow();handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
