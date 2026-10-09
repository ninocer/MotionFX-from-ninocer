package dev.motionfx

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.motionfx.core.*
import dev.motionfx.plugins.*
import dev.motionfx.render.*
import dev.motionfx.storage.ProjectStore
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale
import java.util.UUID

class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xffaf91ff),background=Color(0xff111116),surface=Color(0xff1c1b24))) {
            Surface(Modifier.fillMaxSize()) { Editor() }
        } }
    }
}

@Composable private fun Editor() {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val store=remember{ProjectStore(context)}; val plugins=remember{PluginStore(context)}
    val history=remember{History()}
    var ru by remember{mutableStateOf(Locale.getDefault().language=="ru")}
    fun tr(en:String,rus:String)=if(ru)rus else en
    var project by remember{mutableStateOf(Project(name="MotionFX"))}
    var loaded by remember{mutableStateOf(false)}
    var selected by remember{mutableStateOf<String?>(null)}
    var time by remember{mutableLongStateOf(0L)}
    var playing by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf("")}
    var error by remember{mutableStateOf<String?>(null)}
    var dialog by remember{mutableStateOf("")}
    var projects by remember{mutableStateOf(emptyList<Pair<String,String>>())}
    var installed by remember{mutableStateOf(emptyList<PluginManifest>())}
    var pluginRevision by remember{mutableIntStateOf(0)}
    var exportJob by remember{mutableStateOf<Job?>(null)}
    var progress by remember{mutableFloatStateOf(0f)}
    var output by remember{mutableStateOf<Uri?>(null)}
    var exportHeight by remember{mutableIntStateOf(720)}
    var exportFps by remember{mutableIntStateOf(30)}
    var bitrate by remember{mutableFloatStateOf(8f)}
    var hevc by remember{mutableStateOf(false)}
    var zoom by remember{mutableFloatStateOf(1f)}
    var tab by remember{mutableIntStateOf(0)}
    val layer=project.layers.find{it.id==selected}
    fun change(next:Project) {
        try { next.validated(); history.push(project); project=next }
        catch(t:Exception){error=t.message?:t.toString()}
    }
    fun edit(next:Layer) { if(layer?.locked!=true) change(project.replace(next)) }
    fun fail(t:Throwable) { if(t !is CancellationException) error=t.message?:t.toString() }
    suspend fun save(p:Project) { withContext(Dispatchers.IO){store.save(p)} }
    val prefs=remember{context.getSharedPreferences("editor",0)}
    LaunchedEffect(Unit) {
        try {
            val id=prefs.getString("last",null)
            if(id!=null) project=withContext(Dispatchers.IO){store.load(id)}
            installed=withContext(Dispatchers.IO){plugins.list()}
        } catch(t:Exception){fail(t)}
        loaded=true
    }
    LaunchedEffect(project,loaded) {
        if(loaded) try { delay(350); save(project); prefs.edit().putString("last",project.id).apply(); status=tr("Saved locally","Сохранено") }
        catch(t:Exception){fail(t)}
    }
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner,project) {
        val snapshot=project
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP) {
            playing=false
            // Flush the debounced snapshot before process death; AtomicFile retains the previous valid version on failure.
            try { store.save(snapshot); prefs.edit().putString("last",snapshot.id).apply() } catch(t:Exception){fail(t)}
        } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(playing) {
        if(playing) {
            val start=SystemClock.elapsedRealtime()-time
            while(isActive) { val next=SystemClock.elapsedRealtime()-start
                if(next>=project.duration) { time=project.duration-1; playing=false; break }
                time=next; delay(33)
            }
        }
    }
    val renderer=remember{FrameRenderer(context)}
    DisposableEffect(Unit){onDispose{renderer.close()}}
    var preview by remember{mutableStateOf<Bitmap?>(null)}
    // One worker, conflated latest state. Decoding is deliberately limited to reference-preview quality.
    val renderRequests=remember{kotlinx.coroutines.channels.Channel<Pair<Project,Long>>(kotlinx.coroutines.channels.Channel.CONFLATED)}
    LaunchedEffect(project,time){renderRequests.trySend(project to time)}
    LaunchedEffect(Unit) {
        for((p,t) in renderRequests) try {
            val ratio=480f/maxOf(p.width,p.height)
            val bitmap=withContext(Dispatchers.Default){renderer.render(p,t,maxOf(2,(p.width*ratio).toInt()),maxOf(2,(p.height*ratio).toInt()))}
            preview=bitmap // Compose may still draw the old bitmap this frame; let GC release it safely.
        } catch(t:Exception){playing=false; fail(t)}
    }
    val importMedia=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) scope.launch {
            try {
                context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val type=context.contentResolver.getType(uri).orEmpty()
                val isVideo=type.startsWith("video/")
                require(isVideo || type.startsWith("image/")) { "Unsupported media" }
                val duration=if(isVideo) withContext(Dispatchers.IO){MediaMetadataRetriever().use {
                    it.setDataSource(context,uri)
                    it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: error("No video duration")
                }} else project.duration
                val l=Layer(name=if(isVideo)tr("Video","Видео") else tr("Image","Изображение"),kind=if(isVideo)LayerKind.VIDEO else LayerKind.IMAGE,
                    source=uri.toString(),end=minOf(project.duration,duration))
                change(project.copy(layers=project.layers+l)); selected=l.id
            } catch(t:Exception){fail(t)}
        }
    }
    val installPlugin=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) scope.launch { try {
            withContext(Dispatchers.IO){checkNotNull(context.contentResolver.openInputStream(uri)).use{plugins.install(it)}}
            installed=plugins.list(); pluginRevision++
        }catch(t:Exception){fail(t)} }
    }
    val saveDocument=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null) { val snapshot=project; scope.launch { try {
            withContext(Dispatchers.IO) { checkNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use{it.write(ProjectFormat.encode(snapshot))} }
            status=tr("Project document saved","Документ проекта сохранён")
        }catch(t:Exception){fail(t)} } }
    }
    val openDocument=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) scope.launch { try {
            val p=withContext(Dispatchers.IO) { checkNotNull(context.contentResolver.openInputStream(uri)).use{
                val bytes=it.readNBytes(4*1024*1024+1); require(bytes.size<=4*1024*1024)
                ProjectFormat.decode(bytes.toString(Charsets.UTF_8))
            } }.copy(id=UUID.randomUUID().toString())
            save(project); save(p); history.clear(); playing=false; project=p; selected=null; time=0
        }catch(t:Exception){fail(t)} }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("MotionFX",style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.primary,modifier=Modifier.weight(1f))
            TextButton(onClick={ru=!ru}){Text(if(ru)"EN" else "RU")}
            TextButton(onClick={playing=false; dialog="projects"; scope.launch{projects=withContext(Dispatchers.IO){store.list()}}}){Text(tr("Projects","Проекты"))}
            Button(enabled=loaded && exportJob==null,onClick={playing=false; dialog="export"}){Text(tr("Export","Экспорт"))}
        }
        Text(project.name+" · ${project.width}×${project.height} · ${project.fps} FPS",style=MaterialTheme.typography.labelSmall)
        Box(Modifier.fillMaxWidth().heightIn(min=130.dp,max=220.dp).weight(.8f).background(Color.Black),contentAlignment=Alignment.Center) {
            preview?.let{Image(it.asImageBitmap(),tr("Composition preview","Предпросмотр композиции"),Modifier.fillMaxSize())}
        }
        Text(tr("Reference preview · video audio is not included","Черновой предпросмотр · без звуковой дорожки"),style=MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick={project=history.undo(project)}){Text("↶")}
            TextButton(onClick={playing=false; time=maxOf(0,time-1000/project.fps)}){Text("−1f")}
            Button(onClick={if(time>=project.duration-1)time=0; playing=!playing}){Text(if(playing)"Ⅱ" else "▶")}
            TextButton(onClick={playing=false; time=minOf(project.duration-1,time+1000/project.fps)}){Text("+1f")}
            TextButton(onClick={project=history.redo(project)}){Text("↷")}
            Text("%.2f s".format(Locale.US,time/1000f),style=MaterialTheme.typography.labelSmall)
        }
        Slider(value=time.toFloat(),onValueChange={playing=false; time=it.toLong()},valueRange=0f..(project.duration-1).coerceAtLeast(1).toFloat())
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick={importMedia.launch(arrayOf("video/*","image/*"))}){Text(tr("+ Media","+ Медиа"))}
            TextButton(onClick={val l=Layer(name=tr("Text","Текст"),kind=LayerKind.TEXT,end=project.duration);change(project.copy(layers=project.layers+l));selected=l.id}){Text(tr("+ Text","+ Текст"))}
            TextButton(onClick={val l=Layer(name=tr("Shape","Фигура"),end=project.duration);change(project.copy(layers=project.layers+l));selected=l.id}){Text(tr("+ Shape","+ Фигура"))}
            TextButton(onClick={scope.launch{try{save(project);status=tr("Saved","Сохранено")}catch(t:Exception){fail(t)}}}){Text(tr("Save","Сохранить"))}
            TextButton(onClick={dialog="plugins"}){Text(tr("Plugins","Плагины"))}
        }
        Row { listOf(tr("Timeline","Таймлайн"),tr("Properties","Свойства"),tr("Effects","Эффекты")).forEachIndexed { i,label ->
            TextButton(onClick={tab=i},modifier=Modifier.weight(1f)){Text(if(i==tab)"• $label" else label)}
        } }
        Box(Modifier.weight(1f)) {
            when(tab) {
                0 -> Column {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(tr("Zoom","Масштаб")); Slider(zoom,{zoom=it},valueRange=1f..8f,modifier=Modifier.weight(1f))
                    }
                    val scroll=rememberScrollState()
                    LazyColumn(Modifier.fillMaxSize().pointerInput(Unit){detectTransformGestures { _,_,z,_ -> zoom=(zoom*z).coerceIn(1f,8f) }}) {
                        items(project.layers.reversed(),key={it.id}) { l ->
                            Column(Modifier.fillMaxWidth().clickable{selected=l.id}.background(if(l.id==selected)Color(0xff343046) else Color.Transparent).padding(5.dp)) {
                                Text("${if(l.visible)"●" else "○"} ${l.name}${if(l.locked)" 🔒" else ""}",style=MaterialTheme.typography.labelMedium)
                                Box(Modifier.horizontalScroll(scroll).fillMaxWidth()) {
                                    val full=300f*zoom
                                    Box(Modifier.width(full.dp).height(26.dp).background(Color(0xff252530))) {
                                        Box(Modifier.offset(x=(full*l.start/project.duration).dp).width((full*(l.end-l.start)/project.duration).dp).fillMaxHeight().background(Color(0xff7253bc)))
                                        Box(Modifier.offset(x=(full*time/project.duration).dp).width(2.dp).fillMaxHeight().background(Color.White))
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> if(layer==null) Text(tr("Select or add a layer","Выберите или добавьте слой")) else Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(layer.name,{edit(layer.copy(name=it))},label={Text(tr("Layer name","Имя слоя"))},singleLine=true)
                    if(layer.kind==LayerKind.TEXT) OutlinedTextField(layer.text,{edit(layer.copy(text=it))},label={Text(tr("Text","Текст"))},singleLine=true)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick={change(project.replace(layer.copy(visible=!layer.visible)))}){Text(tr("Visibility","Видимость"))}
                        TextButton(onClick={change(project.replace(layer.copy(locked=!layer.locked)))}){Text(if(layer.locked)tr("Unlock","Разблокировать") else tr("Lock","Блокировать"))}
                        TextButton(enabled=!layer.locked,onClick={val l=layer.copy(id=UUID.randomUUID().toString(),name=layer.name+" copy");change(project.copy(layers=project.layers+l));selected=l.id}){Text(tr("Duplicate","Дубликат"))}
                        TextButton(enabled=!layer.locked,onClick={change(project.copy(layers=project.layers.filterNot{it.id==layer.id}));selected=null}){Text(tr("Delete","Удалить"))}
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(enabled=!layer.locked && time>layer.start && time<layer.end,onClick={change(project.split(layer.id,time))}){Text(tr("Split here","Разделить"))}
                        TextButton(enabled=!layer.locked && time<layer.end,onClick={edit(layer.copy(start=time,sourceIn=maxOf(0,layer.mediaTime(time))))}){Text(tr("Trim start","Начало здесь"))}
                        TextButton(enabled=!layer.locked && time>layer.start,onClick={edit(layer.copy(end=time))}){Text(tr("Trim end","Конец здесь"))}
                        TextButton(enabled=!layer.locked,onClick={val ls=project.layers.toMutableList(); val i=ls.indexOf(layer); if(i<ls.lastIndex){java.util.Collections.swap(ls,i,i+1);change(project.copy(layers=ls))}}){Text(tr("Raise","Выше"))}
                        TextButton(enabled=!layer.locked,onClick={val ls=project.layers.toMutableList(); val i=ls.indexOf(layer); if(i>0){java.util.Collections.swap(ls,i,i-1);change(project.copy(layers=ls))}}){Text(tr("Lower","Ниже"))}
                    }
                    Property("X",layer.transform.x,time,0f..1f,!layer.locked){edit(layer.copy(transform=layer.transform.copy(x=it)))}
                    Property("Y",layer.transform.y,time,0f..1f,!layer.locked){edit(layer.copy(transform=layer.transform.copy(y=it)))}
                    Property(tr("Scale","Масштаб"),layer.transform.scale,time,.05f..3f,!layer.locked){edit(layer.copy(transform=layer.transform.copy(scale=it)))}
                    Property(tr("Rotation","Поворот"),layer.transform.rotation,time,-180f..180f,!layer.locked){edit(layer.copy(transform=layer.transform.copy(rotation=it)))}
                    Property(tr("Opacity","Непрозрачность"),layer.transform.opacity,time,0f..1f,!layer.locked){edit(layer.copy(transform=layer.transform.copy(opacity=it)))}
                    Row { listOf(0xff9875ff.toInt(),0xffffffff.toInt(),0xfffa778a.toInt(),0xff6fe4cc.toInt()).forEach{color ->
                        TextButton(onClick={edit(layer.copy(color=color))}){Text("●",color=Color(color))}
                    } }
                    Text(tr("◆ adds a key at the playhead. With keys, sliders update the current key.","◆ добавляет ключ в позиции курсора. При наличии ключей ползунок меняет ключ в текущем времени."),style=MaterialTheme.typography.bodySmall)
                }
                2 -> if(layer==null) Text(tr("Select a layer","Выберите слой")) else Column(Modifier.verticalScroll(rememberScrollState())) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) { PluginApi.supportedEffects.forEach { kind ->
                        TextButton(enabled=!layer.locked,onClick={edit(layer.copy(effects=layer.effects+Effect(kind)))}){Text("+ $kind")}
                    } }
                    layer.effects.forEachIndexed { index,effect ->
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(effect.enabled,{value -> edit(layer.copy(effects=layer.effects.mapIndexed{i,e->if(i==index)e.copy(enabled=value)else e}))})
                            Text(effect.kind,Modifier.weight(1f))
                            TextButton(onClick={edit(layer.copy(effects=layer.effects.filterIndexed{i,_->i!=index}))}){Text("×")}
                        }
                        Property(effect.pluginId?:effect.kind,effect.amount,time,-1f..1f,!layer.locked){track->edit(layer.copy(effects=layer.effects.mapIndexed{i,e->if(i==index)e.copy(amount=track)else e}))}
                    }
                }
            }
        }
        Text(status,maxLines=1,style=MaterialTheme.typography.labelSmall)
        if(exportJob!=null) { LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth()); TextButton(onClick={exportJob?.cancel()}){Text(tr("Cancel export","Отменить экспорт"))} }
        output?.let{uri->TextButton(onClick={try{context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}catch(t:Exception){fail(t)}}){Text(tr("Open exported video","Открыть экспортированное видео"))}}
    }
    if(dialog=="projects") AlertDialog(onDismissRequest={dialog=""},title={Text(tr("Projects","Проекты"))},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        projects.forEach{(id,name)->Row(verticalAlignment=Alignment.CenterVertically){
            TextButton(onClick={scope.launch{try{save(project);project=withContext(Dispatchers.IO){store.load(id)};time=0;selected=null;history.clear();dialog=""}catch(t:Exception){fail(t)}}},modifier=Modifier.weight(1f)){Text(name)}
            TextButton(onClick={scope.launch{try{val p=withContext(Dispatchers.IO){store.load(id)}.copy(id=UUID.randomUUID().toString(),name="$name copy");save(p);projects=store.list()}catch(t:Exception){fail(t)}}}){Text("⧉")}
            TextButton(enabled=id!=project.id,onClick={scope.launch{try{withContext(Dispatchers.IO){store.delete(id)};projects=store.list()}catch(t:Exception){fail(t)}}}){Text("×")}
        }}
        TextButton(onClick={dialog="";saveDocument.launch("${project.name}.mfx")}){Text(tr("Save .mfx document","Сохранить документ .mfx"))}
        TextButton(onClick={dialog="";openDocument.launch(arrayOf("application/json","application/octet-stream","*/*"))}){Text(tr("Import .mfx","Импорт .mfx"))}
    }},confirmButton={TextButton(onClick={dialog="new"}){Text(tr("New project","Новый проект"))}},dismissButton={TextButton(onClick={dialog=""}){Text(tr("Close","Закрыть"))}})
    if(dialog=="new") NewProjectDialog(ru,onDismiss={dialog=""}) { p -> scope.launch{try{save(project);save(p);project=p;history.clear();selected=null;time=0;dialog=""}catch(t:Exception){fail(t)}} }
    if(dialog=="plugins") AlertDialog(onDismissRequest={dialog=""},title={Text(tr("Plugin manager · API 1","Менеджер плагинов · API 1"))},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(tr("Declarative effect presets. Applying copies effects into the project; disabling a package prevents new applications.","Декларативные пресеты эффектов. При применении эффекты копируются в проект; отключение пакета запрещает новые применения."))
        installed.forEach{m->Column{
            Text("${m.name} ${m.version}")
            Row { Checkbox(remember(pluginRevision,m.id){plugins.enabled(m.id)},{plugins.enable(m.id,it);pluginRevision++})
                TextButton(enabled=layer!=null && !layer.locked && plugins.enabled(m.id),onClick={layer?.let{edit(it.copy(effects=it.effects+PluginApi.instantiate(m)))};dialog=""}){Text(tr("Apply","Применить"))}
                TextButton(onClick={try{plugins.remove(m);installed=plugins.list();pluginRevision++}catch(t:Exception){fail(t)}}){Text("×")}
            }
        }}
    }},confirmButton={TextButton(onClick={installPlugin.launch(arrayOf("*/*"))}){Text(tr("Install .mfxplugin","Установить .mfxplugin"))}},dismissButton={TextButton(onClick={dialog=""}){Text(tr("Close","Закрыть"))}})
    if(dialog=="export") AlertDialog(onDismissRequest={dialog=""},title={Text(tr("Export composition","Экспорт композиции"))},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(tr("MP4 has no audio in v0.1. Keep the app open during export. Size/FPS support is checked on your device.","В версии 0.1 MP4 без звука. Оставьте приложение открытым во время экспорта. Размер и FPS проверяются на устройстве."))
        Row(Modifier.horizontalScroll(rememberScrollState())) { listOf(720,1080,1440,2160).forEach{h->FilterChip(exportHeight==h,{exportHeight=h},label={Text("${h}p")})} }
        Row { listOf(24,30,60).forEach{fps->FilterChip(exportFps==fps,{exportFps=fps},label={Text("$fps FPS")})} }
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(hevc,{hevc=it});Text("H.265 / HEVC")}
        Text("${bitrate.toInt()} Mbps"); Slider(bitrate,{bitrate=it},valueRange=1f..40f)
        TextButton(onClick={dialog="";val p=project;val t=time;scope.launch{try{
            val uri=withContext(Dispatchers.Default){FrameRenderer(context).use{r->val b=r.render(p,t);val f=File.createTempFile("frame-",".png",context.cacheDir)
                try{f.outputStream().use{check(b.compress(Bitmap.CompressFormat.PNG,100,it))};VideoExporter(context).publish(f,"image/png","png")}finally{b.recycle();f.delete()}}}
            status=tr("PNG saved to Pictures/MotionFX","PNG сохранён в Pictures/MotionFX")
        }catch(t:Exception){fail(t)}}}){Text(tr("Save current frame as PNG","Сохранить кадр PNG"))}
    }},confirmButton={Button(onClick={
        dialog="";playing=false;progress=0f;output=null
        val snapshot=project;val ratio=exportHeight.toFloat()/minOf(project.width,project.height)
        val s=ExportSettings((project.width*ratio/2).toInt()*2,(project.height*ratio/2).toInt()*2,exportFps,(bitrate*1_000_000).toInt(),if(hevc)"video/hevc"else"video/avc")
        exportJob=scope.launch { try { output=VideoExporter(context).export(snapshot,s){v->scope.launch{progress=v}};status=tr("MP4 saved to Movies/MotionFX","MP4 сохранён в Movies/MotionFX") }
            catch(t:CancellationException){status=tr("Export cancelled","Экспорт отменён")}
            catch(t:Exception){fail(t)} finally{exportJob=null} }
    }){Text(tr("Export MP4","Экспорт MP4"))}},dismissButton={TextButton(onClick={dialog=""}){Text(tr("Close","Закрыть"))}})
    error?.let { message -> AlertDialog(onDismissRequest={error=null},title={Text(tr("Operation failed","Ошибка операции"))},text={Text(message)},confirmButton={TextButton(onClick={error=null}){Text("OK")}}) }
}

@Composable private fun Property(name:String,track:Track,time:Long,range:ClosedFloatingPointRange<Float>,enabled:Boolean,onChange:(Track)->Unit) {
    var easing by remember(name){mutableStateOf(Easing.LINEAR)}
    Row(verticalAlignment=Alignment.CenterVertically) {
        Text("$name ${"%.2f".format(Locale.US,track.at(time))}",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
        TextButton(enabled=enabled,onClick={onChange(track.key(time,track.at(time),easing))}){Text("◆ ${track.keys.size}")}
        TextButton(enabled=enabled,onClick={onChange(track.copy(keys=track.keys.filterNot{it.time==time}))}){Text("−◆")}
        TextButton(enabled=enabled,onClick={easing=Easing.entries[(easing.ordinal+1)%Easing.entries.size];if(track.keys.any{it.time==time})onChange(track.key(time,track.at(time),easing))}){Text(easing.name,style=MaterialTheme.typography.labelSmall)}
    }
    Slider(track.at(time).coerceIn(range),{v->onChange(if(track.keys.isEmpty())track.copy(value=v)else track.key(time,v,easing))},enabled=enabled,valueRange=range)
}
@Composable private fun NewProjectDialog(ru:Boolean,onDismiss:()->Unit,onCreate:(Project)->Unit) {
    var name by remember{mutableStateOf("MotionFX")};var width by remember{mutableStateOf("1280")};var height by remember{mutableStateOf("720")}
    var duration by remember{mutableStateOf("5")};var fps by remember{mutableIntStateOf(30)}
    val candidate=runCatching{Project(name=name,width=width.toInt(),height=height.toInt(),fps=fps,duration=(duration.toDouble()*1000).toLong()).validated()}.getOrNull()
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(ru)"Новая композиция"else"New composition")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        OutlinedTextField(name,{name=it},label={Text(if(ru)"Название"else"Name")},singleLine=true)
        Row(Modifier.horizontalScroll(rememberScrollState())) { listOf("16:9" to (1280 to 720),"9:16" to (720 to 1280),"1:1" to (1080 to 1080),"4:3" to (960 to 720)).forEach{(label,size)->TextButton(onClick={width=size.first.toString();height=size.second.toString()}){Text(label)}} }
        OutlinedTextField(width,{width=it},label={Text(if(ru)"Ширина (чётная)"else"Width (even)")},singleLine=true)
        OutlinedTextField(height,{height=it},label={Text(if(ru)"Высота (чётная)"else"Height (even)")},singleLine=true)
        OutlinedTextField(duration,{duration=it},label={Text(if(ru)"Длительность, секунды"else"Duration, seconds")},singleLine=true)
        Row{listOf(24,30,60).forEach{v->FilterChip(fps==v,{fps=v},label={Text("$v FPS")})}}
    }},confirmButton={Button(enabled=candidate!=null,onClick={candidate?.let(onCreate)}){Text(if(ru)"Создать"else"Create")}},dismissButton={TextButton(onClick=onDismiss){Text(if(ru)"Отмена"else"Cancel")}})
}
