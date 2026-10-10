package com.example.otrdial

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

class PlaybackMiniBar(context: Context): LinearLayout(context) {
    private var future: ListenableFuture<MediaController>? = null
    private var player: MediaController? = null
    private val title=TextView(context).apply { textSize=14f; setTextColor(context.getColor(R.color.otr_ink)); maxLines=2; setPadding(16,8,16,8); contentDescription="Open current player" }
    private val toggle=ImageButton(context).apply { setBackgroundColor(android.graphics.Color.TRANSPARENT); imageTintList=android.content.res.ColorStateList.valueOf(context.getColor(R.color.otr_brown)) }
    private val listener=object: Player.Listener { override fun onEvents(p: Player, e: Player.Events) { update() } }
    init {
        orientation=HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; minimumHeight=(56*resources.displayMetrics.density).toInt(); setBackgroundResource(R.drawable.glass_panel)
        addView(title,LayoutParams(0,-2,1f)); val size=(52*resources.displayMetrics.density).toInt(); addView(toggle,LayoutParams(size,size))
        title.setOnClickListener { context.startActivity(Intent(context,LibraryActivity::class.java).putExtra("player",true).putExtra("from_collection",true)) }
        toggle.setOnClickListener { player?.let { if(it.playWhenReady) it.pause() else { if(it.playbackState==Player.STATE_IDLE) it.prepare(); it.play() } } }
        visibility=View.GONE
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val requested=MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync(); future=requested
        requested.addListener({ if(future===requested && isAttachedToWindow) runCatching { requested.get() }.onSuccess { player=it; it.addListener(listener); update() } },ContextCompat.getMainExecutor(context))
    }
    private fun update() { val p=player; visibility=if(p?.currentMediaItem!=null) View.VISIBLE else View.GONE; title.text=p?.mediaMetadata?.title ?: "Open player"; val paused=p?.playWhenReady!=true; toggle.setImageResource(if(paused) R.drawable.ic_play else R.drawable.ic_pause); toggle.contentDescription=if(paused) "Play" else "Pause" }
    override fun onDetachedFromWindow() { player?.removeListener(listener); player=null; future?.let { MediaController.releaseFuture(it) }; future=null; super.onDetachedFromWindow() }
}
