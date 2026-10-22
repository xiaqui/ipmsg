package com.xiaqui.ipmsg

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.provider.Settings
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.*
import java.text.DateFormat
import java.util.Date
import java.security.KeyPair
import android.net.Uri

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var pair: KeyPair
    private lateinit var self: String
    private lateinit var peersView: LinearLayout
    private lateinit var stateView: TextView
    private lateinit var selectionView: TextView
    private lateinit var composer: EditText
    private lateinit var allButton: Button
    private lateinit var selfButton: Button
    private val selected = linkedSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings",MODE_PRIVATE) }
    private var rendered = ""
    private var pendingTreeTransfer: Transfer? = null
    private val fileManager by lazy { FileTransferManager(applicationContext,store,{ handler.post { rendered="";renderPeers() } },{ _,_-> }) }
    private val refresh = object : Runnable {
        override fun run() { renderPeers(); handler.postDelayed(this,2000) }
    }
    private fun name() = prefs.getString("name",Build.MODEL)!!
    private fun text(value: String,size: Float = 16f) = TextView(this).apply { text=value; textSize=size;setPadding(0,8,0,8) }
    private fun button(value: String,action: () -> Unit) = Button(this).apply { text=value;setOnClickListener { action() } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store=Store(this)
        // RSA identity is generated once in Android Keystore and survives process/OS restarts.
        pair=Identity.load(); self=Protocol.id(pair.public)
        savedInstanceState?.getStringArrayList("selected")?.let { selected.addAll(it) }
        val root = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(250,248,245))
            setPadding(24,16,24,16)
            setOnApplyWindowInsetsListener { view,insets ->
                view.setPadding(24+insets.systemWindowInsetLeft,16+insets.systemWindowInsetTop,
                    24+insets.systemWindowInsetRight,16+insets.systemWindowInsetBottom)
                insets
            }
        }
        val scroll=ScrollView(this).apply { isFillViewport=true;addView(root) }
        setContentView(scroll)
        root.addView(text("IP Messenger",28f))
        root.addView(text("この端末："+name()))
        stateView=text("");root.addView(stateView)
        allButton=button("📢 全端末（自分を含む）") {
            selected.clear(); selected.add(self)
            selected.addAll(store.peers().filter { it.trusted }.map { it.id })
            rendered="";renderPeers()
        }.apply { textSize=21f;setTextColor(Color.WHITE);setBackgroundColor(Color.rgb(160,25,40)) }
        root.addView(allButton)
        selfButton=button("📝 自分宛て") { selected.clear();selected.add(self);rendered="";renderPeers() }
        root.addView(selfButton)
        root.addView(text("宛先をタップして個別／複数選択。返信も個別の手紙です。",14f))
        peersView=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };root.addView(peersView)
        selectionView=text("");root.addView(selectionView)
        composer=EditText(this).apply {
            id=R.id.composer;hint="手紙を書く（UTF-8で8KBまで）";minLines=3;maxLines=10
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        };root.addView(composer)
        root.addView(button("宛先を確認して送信") { confirmSend() })
        root.addView(button("ファイルを選んで送信") { pickFiles() })
        root.addView(button("受信箱") { history(true) })
        root.addView(button("送信履歴・保留") { history(false) })
        root.addView(button("ファイル転送") { fileTransfers() })
        root.addView(button("設定・端末承認") { settings() })
        root.addView(button("LAN診断") { showLanDiagnostics() })
        if(intent.getBooleanExtra("inbox",false)) history(true)
        if(intent.getBooleanExtra("files",false)) fileTransfers()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("selected",ArrayList(selected))
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        if(prefs.getBoolean("enabled",false) && !MessengerService.active) startReceiver()
        handler.post(refresh)
    }
    override fun onPause() { handler.removeCallbacks(refresh);super.onPause() }
    override fun onDestroy() { store.close();super.onDestroy() }
    private fun startReceiver() {
        try { MessengerService.start(this) }
        catch(_: RuntimeException) { toast("受信開始に失敗しました。設定から再開してください") }
    }
    private fun toast(value: String) { Toast.makeText(this,value,Toast.LENGTH_LONG).show() }
    private fun renderPeers() {
        val peers=store.peers()
        selected.retainAll(peers.filter { it.trusted }.map { it.id }.toSet()+self)
        val now=System.currentTimeMillis()
        stateView.text=if(MessengerService.active) MessengerService.engine?.status ?: "受信準備中"
            else MessengerService.problem.ifEmpty { "受信停止中：設定から受信を開始してください" }
        if(Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            stateView.append("\n通知が無効です。設定から許可してください")
        val stamp=peers.joinToString { it.id+it.name+it.trusted+it.online(now) }+selected.toString()
        if(stamp!=rendered) {
            rendered=stamp;peersView.removeAllViews()
            val own=CheckBox(this).apply {
                text="この端末（自分）";isChecked=self in selected
                setOnCheckedChangeListener { _,value -> if(value) selected.add(self) else selected.remove(self);rendered="";renderPeers() }
            };peersView.addView(own)
            peers.forEach { peer ->
                if(peer.trusted) {
                    peersView.addView(CheckBox(this).apply {
                        val online=peer.online(now)
                        val label="● "+peer.name+
                            (if(online) "" else "（オフライン・保留送信）")
                        text=SpannableString(label).apply {
                            setSpan(ForegroundColorSpan(
                                if(online) Color.rgb(35,155,55) else Color.DKGRAY
                            ),0,1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        isChecked=peer.id in selected
                        setOnCheckedChangeListener { _,value ->
                            if(value) selected.add(peer.id) else selected.remove(peer.id);rendered="";renderPeers()
                        }
                    })
                } else {
                    peersView.addView(button("未承認："+peer.name+" → 指紋を照合") { approve(peer) })
                }
            }
        }
        val targets=selected.map { id -> if(id==self) name()+"（自分）" else peers.find { it.id==id }?.name ?: id.take(8) }
        selectionView.text="宛先 "+targets.size+"台："+targets.joinToString("、")
        allButton.text="📢 全端末 "+(peers.count { it.trusted }+1)+"台（自分を含む）"
    }
    private fun confirmSend() {
        val body=composer.text.toString()
        if(!Protocol.validText(body)) { toast("本文を入力してください（UTF-8で8KBまで）");return }
        if(selected.isEmpty()) { toast("宛先を選んでください");return }
        val targets=selected.toSet()
        val labels=targets.map { id -> if(id==self) name()+"（自分）" else store.peers().find { it.id==id }?.name ?: id }
        AlertDialog.Builder(this).setTitle("この"+targets.size+"台に送信")
            .setMessage(labels.joinToString("\n")+"\n\nオフライン宛ては保留します。")
            .setNegativeButton("戻る",null).setPositiveButton("送信") { _,_ ->
                runCatching { store.enqueue(targets,self,name(),body) }
                    .onSuccess { composer.setText("");toast("送信履歴に保存しました。到着はACK受信後に表示します") }
                    .onFailure { toast("保存できませんでした。宛先と空き容量を確認してください") }
            }.show()
    }
    private fun pickFiles() {
        val targets=selected.toList()
        if(targets.isEmpty()) { toast("宛先を選んでください");return }
        val peers=store.peers().associateBy { it.id }
        val unsupported=targets.filter { it!=self }.mapNotNull { peers[it] }.filter { !it.fileCapable }.map { it.name }
        if(unsupported.isNotEmpty()) { toast("ファイル転送非対応: "+unsupported.joinToString("、"));return }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE);type="*/*";putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        },20)
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK||data==null)return
        if(requestCode==20) {
            val uris=buildList<Uri> {
                data.clipData?.let { clip -> for(index in 0 until clip.itemCount) add(clip.getItemAt(index).uri) }
                    ?:data.data?.let(::add)
            }.distinct()
            if(uris.isEmpty()) return
            uris.forEach { uri -> runCatching { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            val targets=selected.toSet()
            Thread {
                val result=runCatching { fileManager.queue(targets,fileManager.describe(uris),self,name()) }
                handler.post { if(result.isSuccess) toast("ファイル転送を開始しました")
                    else toast("ファイルを送信できません: "+result.exceptionOrNull()?.message.orEmpty()) }
            }.start()
        } else if(requestCode==21) {
            val transfer=pendingTreeTransfer ?: return;val tree=data.data ?: return
            runCatching { contentResolver.takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            runCatching { fileManager.accept(transfer,tree) }
                .onSuccess { toast("受信を開始しました") }.onFailure { toast("保存先を使用できません: "+it.message.orEmpty()) }
            pendingTreeTransfer=null;fileTransfers()
        }
    }
    private fun fileTransfers() {
        val transfers=store.transfers()
        val labels=transfers.map { transfer ->
            val direction=if(transfer.incoming) "受信" else "送信"
            val files=store.transferFiles(transfer.id)
            val done=files.sumOf { it.offset };val total=files.sumOf { it.size }
            "$direction ${transfer.peerName} [${transfer.state}]\n${files.joinToString("、") { it.name }}\n$done / $total bytes"+
                if(transfer.error.isEmpty()) "" else "\n${transfer.error}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("ファイル転送").setItems(labels) { _,index -> showTransfer(transfers[index]) }
            .setNegativeButton("閉じる",null).show()
    }
    private fun showTransfer(transfer:Transfer) {
        val files=store.transferFiles(transfer.id)
        val message=files.joinToString("\n\n") { file ->
            val percent=if(file.size==0L&&file.state=="COMPLETED") 100 else file.offset*100/maxOf(1,file.size)
            "${file.name}\n${file.offset}/${file.size} bytes（$percent%） ${file.state}"
        }+(if(transfer.error.isEmpty()) "" else "\n\nエラー: ${transfer.error}")
        val builder=AlertDialog.Builder(this).setTitle(transfer.peerName).setMessage(message).setNegativeButton("閉じる",null)
        if(transfer.incoming&&transfer.state in setOf("WAITING_ACCEPT","PAUSED")) builder.setPositiveButton("受信して保存先を選択") { _,_ ->
            pendingTreeTransfer=transfer
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            },21)
        }
        if(transfer.state !in setOf("COMPLETED","CANCELLED","DECLINED")) builder.setNeutralButton("拒否／取消") { _,_ ->
            AlertDialog.Builder(this).setTitle("転送を中止").setMessage("この転送を取り消しますか？")
                .setNegativeButton("戻る",null).setPositiveButton("中止") { _,_ -> fileManager.decline(transfer);toast("中止しました") }.show()
        } else if(transfer.incoming&&transfer.state=="COMPLETED"&&files.any { it.finalPath.isNotEmpty() })
            builder.setPositiveButton("完了ファイルを選んで開く") { _,_ ->
                val completed=files.filter { it.finalPath.isNotEmpty() }
                AlertDialog.Builder(this).setTitle("開くファイル").setItems(completed.map { it.name }.toTypedArray()) { _,index ->
                    val file=completed[index]
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(file.finalPath),file.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { toast("対応するアプリがありません") }
                }.setNegativeButton("閉じる",null).show()
            }
        builder.show()
    }
    private fun fingerprint(id: String) = id.chunked(4).joinToString(" ")
    private fun approve(peer: Peer) {
        AlertDialog.Builder(this).setTitle(peer.name+" を承認")
            .setMessage("相手端末の「設定」にある自分の指紋と、下の全桁が一致するか直接比較してください。相手端末でもこの端末を承認してください。\n\n"+fingerprint(peer.id))
            .setNegativeButton("キャンセル",null).setPositiveButton("全桁を照合・一致") { _,_ ->
                store.trust(peer.id,true);rendered="";renderPeers()
            }.show()
    }
    private fun history(incoming: Boolean) {
        val letters=store.letters(incoming)
        val labels=letters.map {
            DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(it.created))+
                " "+it.name+"\n"+(if(incoming) "到着" else if(it.state=="DELIVERED") "到着確認済み" else "保留／再送待ち（"+it.attempts+"回）")+
                " "+it.body.take(45)
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle(if(incoming) "受信箱" else "送信履歴")
            .setItems(labels) { _,index ->
                val letter=letters[index]
                showLetter(letter)
            }.setNegativeButton("閉じる",null)
            .setNeutralButton("保留を再送") { _,_ ->
                MessengerService.engine?.wake() ?: store.retry()
                toast("承認済みの相手へ、探索と安全な再送を試します")
            }.show()
    }
    internal fun showLetter(letter: Letter) {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,8,24,8) }
        box.addView(TextView(this).apply { text=letter.body;setTextIsSelectable(true) })
        val dialog=AlertDialog.Builder(this).setTitle(letter.name)
            .setView(ScrollView(this).apply { addView(box) }).setNegativeButton("閉じる",null).create()
        box.addView(button("本文をコピー") {
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("本文",letter.body))
            toast("本文をコピーしました")
        })
        box.addView(button("削除") {
            AlertDialog.Builder(this).setTitle("この端末の記録を1件削除")
                .setMessage("相手端末の履歴は削除されません。保留中の場合は今後の再送を停止します。既に送信された本文は取り消せません。")
                .setNegativeButton("キャンセル",null).setPositiveButton("削除") { _,_ ->
                    Thread {
                        val worker=Store(applicationContext)
                        val result=runCatching { worker.deleteLetter(letter) }
                        worker.close()
                        handler.post { if(!isFinishing) {
                            if(result.isSuccess) { dialog.dismiss();toast("削除しました") }
                            else toast("削除できませんでした")
                        } }
                    }.start()
                }.show()
        })
        if(letter.incoming && (letter.peer==self || store.peers().any { it.id==letter.peer && it.trusted }))
            dialog.setButton(AlertDialog.BUTTON_POSITIVE,"個別に返信") { _,_ ->
                selected.clear();selected.add(letter.peer);rendered="";renderPeers();composer.requestFocus()
            }
        dialog.show()
    }
    private fun showLanDiagnostics() {
        val report = MessengerService.engine?.diagnosticReport() ?: buildString {
            appendLine("受信サービス停止中")
            appendLine("VPN・UDP・TCP・ACKの実測値は、常駐受信を有効にしてから確認してください。")
            val now=System.currentTimeMillis()
            appendLine("保存済み端末:")
            store.peers().forEach {
                val age=if(it.seen<=0) "未受信" else ((now-it.seen).coerceAtLeast(0)/1000).toString()+"秒前"
                val stamp=if(it.seen<=0) "なし" else DateFormat.getDateTimeInstance(
                    DateFormat.SHORT,DateFormat.MEDIUM).format(Date(it.seen))
                appendLine("  "+LanDiagnostics.safePeerName(it.name)+" "+it.id.take(8)+" "+it.host+
                    " 最終発見:"+stamp+"（"+age+"）"+
                    " "+if(it.online(now)) "ONLINE" else "OFFLINE")
            }
        }
        val reportView=TextView(this).apply {
            text=report;textSize=12f;setTextIsSelectable(true)
            typeface=android.graphics.Typeface.MONOSPACE
            setPadding(24,8,24,8)
        }
        AlertDialog.Builder(this).setTitle("LAN診断")
            .setView(ScrollView(this).apply { addView(reportView) })
            .setPositiveButton("コピー") { _,_ ->
                val clipboard=getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("IP Messenger LAN診断",report))
                toast("LAN診断をコピーしました")
            }
            .setNeutralButton("再探索・再送") { _,_ ->
                MessengerService.engine?.wake() ?: store.retry()
                toast("探索と保留送信を再試行します")
            }
            .setNegativeButton("閉じる",null).show()
    }
    private fun settings() {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,8,24,8) }
        val edit=EditText(this).apply { setText(name());hint="この端末の名前" };box.addView(edit)
        box.addView(text("この端末の指紋（相手側の承認画面と全桁を照合）\n"+fingerprint(self),14f))
        box.addView(text("Wi-Fi内で直接通信します。ゲストWi-Fiの端末間分離では接続できません。消灯中の受信はOSや省電力設定の影響を受けます。",14f))
        box.addView(Switch(this).apply {
            text="常駐受信を有効にする（再起動後も再開）"
            isChecked=prefs.getBoolean("enabled",false)
            setOnCheckedChangeListener { _,enabled ->
                prefs.edit().putBoolean("enabled",enabled).apply()
                if(enabled) startReceiver() else {
                    store.offline()
                    stopService(Intent(this@MainActivity,MessengerService::class.java))
                }
                renderPeers()
            }
        })
        if(Build.VERSION.SDK_INT>=33) box.addView(button("到着通知を許可") {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),10)
        })
        box.addView(button("省電力設定を開く") {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { toast("端末の設定からIP Messengerの省電力制限を確認してください") }
        })
        store.peers().filter { it.trusted }.forEach { peer ->
            box.addView(button(peer.name+" の承認を解除") {
                store.trust(peer.id,false);selected.remove(peer.id);rendered="";renderPeers();toast("承認を解除しました")
            })
        }
        AlertDialog.Builder(this).setTitle("設定・端末承認")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("名前を保存") { _,_ ->
                val value=edit.text.toString().trim().take(40)
                if(value.isNotEmpty()) { prefs.edit().putString("name",value).apply();recreate() }
            }.setNegativeButton("閉じる",null).show()
    }
}


