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

public final class ConversationsActivity extends Activity {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();private final Handler handler=new Handler(Looper.getMainLooper());
    private final MessagingApi api=new MessagingApi();private final List<MessagingApi.Conversation> items=new ArrayList<>();private final Adapter adapter=new Adapter();
    private SessionStore sessions;private RecyclerView list;private boolean destroyed,active,busy;private int page;
    private final Runnable poll=new Runnable(){public void run(){if(!active)return;if(page<=1)load(true,true);handler.postDelayed(this,5000);}};
    @Override public void onCreate(Bundle state){super.onCreate(state);setContentView(R.layout.activity_conversations);UiInsets.apply(findViewById(R.id.conversations_root));sessions=new SessionStore(this);
        list=findViewById(R.id.conversations_list);list.setLayoutManager(new LinearLayoutManager(this));list.setAdapter(adapter);
        findViewById(R.id.conversations_back).setOnClickListener(v->finish());findViewById(R.id.conversations_refresh).setOnClickListener(v->load(true,false));findViewById(R.id.conversations_more).setOnClickListener(v->load(false,false));}
    @Override protected void onResume(){super.onResume();active=true;load(true,false);handler.postDelayed(poll,5000);}
    @Override protected void onPause(){active=false;handler.removeCallbacks(poll);super.onPause();}
    private String token(){String token=sessions.token();if(token==null){startActivity(new Intent(this,AuthActivity.class));finish();}return token;}
    private void load(boolean reset,boolean silent){if(busy)return;String token=token();if(token==null)return;busy=true;controls(false);if(!silent)message(getString(R.string.catalog_loading));int target=reset?1:page+1;
        executor.execute(()->{try{MessagingApi.Conversations value=api.list(token,target);handler.post(()->{if(destroyed)return;busy=false;controls(true);
            Parcelable position=silent?list.getLayoutManager().onSaveInstanceState():null;
            if(reset){int count=items.size();items.clear();adapter.notifyItemRangeRemoved(0,count);}int start=items.size();
            for(MessagingApi.Conversation c:value.items){boolean exists=false;for(MessagingApi.Conversation old:items)if(old.id==c.id){exists=true;break;}if(!exists)items.add(c);}adapter.notifyItemRangeInserted(start,items.size()-start);
            page=value.page;if(position!=null)list.getLayoutManager().onRestoreInstanceState(position);message(items.isEmpty()?getString(R.string.messages_empty):getString(R.string.catalog_count,items.size(),value.total));findViewById(R.id.conversations_more).setVisibility((long)page*value.pageSize<value.total?View.VISIBLE:View.GONE);
        });}catch(IOException e){handler.post(()->{if(destroyed)return;busy=false;controls(true);if(e instanceof UserApi.Failure&&((UserApi.Failure)e).status==401){sessions.clear();token();return;}message(e instanceof UserApi.Failure?e.getMessage():getString(R.string.request_network_error));});}});
    }
    private void controls(boolean enabled){findViewById(R.id.conversations_refresh).setEnabled(enabled);findViewById(R.id.conversations_more).setEnabled(enabled);}
    private void message(String value){((TextView)findViewById(R.id.conversations_status)).setText(value);}
    private final class Adapter extends RecyclerView.Adapter<Holder>{
        public Holder onCreateViewHolder(ViewGroup parent,int type){return new Holder(LayoutInflater.from(ConversationsActivity.this).inflate(R.layout.item_conversation,parent,false));}
        public int getItemCount(){return items.size();}
        public void onBindViewHolder(Holder h,int position){MessagingApi.Conversation c=items.get(position);h.peer.setText(c.peerNickname);h.product.setText(getString(R.string.conversation_product,c.productTitle));h.preview.setText(c.lastMessage.isEmpty()?getString(R.string.conversation_no_message):c.lastMessage);h.time.setText(c.updatedAt);h.unread.setText(c.unreadCount>0?getString(R.string.conversation_unread,c.unreadCount):getString(R.string.conversation_read));h.unread.setVisibility(c.unreadCount>0?View.VISIBLE:View.GONE);h.itemView.setOnClickListener(v->startActivity(new Intent(ConversationsActivity.this,ChatActivity.class).putExtra("conversationId",c.id)));}
    }
    private static final class Holder extends RecyclerView.ViewHolder {final TextView peer,product,preview,time,unread;Holder(View view){super(view);peer=view.findViewById(R.id.conversation_peer);product=view.findViewById(R.id.conversation_product);preview=view.findViewById(R.id.conversation_preview);time=view.findViewById(R.id.conversation_time);unread=view.findViewById(R.id.conversation_unread);}}
    @Override protected void onDestroy(){destroyed=true;executor.shutdownNow();handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
