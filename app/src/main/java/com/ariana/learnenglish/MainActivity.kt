package com.ariana.learnenglish

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.room.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private val Context.dataStore by preferencesDataStore("ariana_settings")
private val Cream = Color(0xFFFAF8F1)
private val Ink = Color(0xFF17231F)
private val Green = Color(0xFF197A68)
private val Gold = Color(0xFFF3C95E)
private val Rose = Color(0xFFE85D75)

class ArianaApp : Application() {
    lateinit var container: AppContainer
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(context: Context) {
    val db = ArianaDatabase.get(context)
    val prefs = ArianaPrefs(context)
    val tts = EnglishTts(context)
    val repo = ArianaRepository(db, prefs)
    init {
        CoroutineScope(Dispatchers.IO).launch { ContentImporter(context, db, prefs).importIfNeeded() }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ArianaApp).container
        setContent { ArianaTheme { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { ArianaNav(container) } } }
    }
}

@Composable
fun ArianaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Green, secondary = Gold, tertiary = Rose, background = Cream, surface = Color.White, onPrimary = Color.White, onSurface = Ink),
        typography = Typography(),
        content = content
    )
}

@Composable
fun ArianaNav(container: AppContainer) {
    val nav = rememberNavController()
    val onboardingDone by container.prefs.onboardingDone.collectAsState(false)
    val start = if (onboardingDone) "home" else "onboarding"
    val scope = rememberCoroutineScope()
    NavHost(navController = nav, startDestination = start) {
        composable("splash") { SplashScreen() }
        composable("onboarding") {
            OnboardingScreen { level, minutes ->
                scope.launch {
                    container.prefs.finishOnboarding(level, minutes)
                    nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                }
            }
        }
        composable("home") {
            val vm: HomeViewModel = viewModel(factory = vmFactory { HomeViewModel(container.repo, container.prefs) })
            HomeScreen(vm, onLesson = { nav.navigate("lesson/$it") }, onPractice = { nav.navigate("practice") }, onProfile = { nav.navigate("profile") }, onSettings = { nav.navigate("settings") })
        }
        composable("lesson/{lessonId}", arguments = listOf(navArgument("lessonId") { type = NavType.StringType })) {
            val id = it.arguments?.getString("lessonId").orEmpty()
            val vm: LessonViewModel = viewModel(key = id, factory = vmFactory { LessonViewModel(id, container.repo, container.prefs, container.tts) })
            LessonScreen(vm, onDone = { nav.navigate("home") { popUpTo("home") } })
        }
        composable("practice") {
            val vm: PracticeViewModel = viewModel(factory = vmFactory { PracticeViewModel(container.repo, container.prefs, container.tts) })
            LessonScreen(vm, onDone = { nav.popBackStack() }, practiceMode = true)
        }
        composable("profile") {
            val vm: ProfileViewModel = viewModel(factory = vmFactory { ProfileViewModel(container.repo, container.prefs) })
            ProfileScreen(vm) { nav.popBackStack() }
        }
        composable("settings") {
            val vm: SettingsViewModel = viewModel(factory = vmFactory { SettingsViewModel(container.repo, container.prefs) })
            SettingsScreen(vm) { nav.popBackStack() }
        }
    }
}

fun <T : ViewModel> vmFactory(create: () -> T) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}

@Entity(tableName = "units") data class UnitEntity(@PrimaryKey val id: String, val titlePs: String, val order: Int)
@Entity(tableName = "lessons") data class LessonEntity(@PrimaryKey val id: String, val unitId: String, val titlePs: String, val order: Int)
@Entity(tableName = "exercises") data class ExerciseEntity(@PrimaryKey val id: String, val lessonId: String, val type: String, val promptPs: String, val promptEn: String?, val optionsJson: String, val answerJson: String, val audioTextEn: String?, val explanationPs: String, val vocabId: String?)
@Entity(tableName = "vocab") data class VocabEntity(@PrimaryKey val id: String, val wordEn: String, val translationPs: String, val transliteration: String?, val exampleEn: String, val examplePs: String)
@Entity(tableName = "lesson_progress") data class UserProgressEntity(@PrimaryKey val lessonId: String, val completed: Boolean, val score: Int, val xp: Int, val lastPlayed: Long)
@Entity(tableName = "vocab_progress") data class VocabProgressEntity(@PrimaryKey val vocabId: String, val mastery: Int, val correctCount: Int, val wrongCount: Int, val lastSeen: Long)
@Entity(tableName = "mistakes") data class MistakeEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val exerciseId: String, val vocabId: String?, val whenSeen: Long)

@Dao interface ArianaDao {
    @Query("select count(*) from units") suspend fun unitCount(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertUnits(items: List<UnitEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLessons(items: List<LessonEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertExercises(items: List<ExerciseEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertVocab(items: List<VocabEntity>)
    @Query("select * from units order by `order`") fun units(): Flow<List<UnitEntity>>
    @Query("select * from lessons order by unitId, `order`") fun lessons(): Flow<List<LessonEntity>>
    @Query("select * from exercises where lessonId=:lessonId order by id") suspend fun exercisesForLesson(lessonId: String): List<ExerciseEntity>
    @Query("select * from exercises where vocabId in (select vocabId from vocab_progress where mastery < 70 union select vocabId from mistakes where vocabId is not null) order by random() limit 10") suspend fun practiceExercises(): List<ExerciseEntity>
    @Query("select * from exercises order by random() limit 10") suspend fun randomExercises(): List<ExerciseEntity>
    @Query("select * from lesson_progress") fun progress(): Flow<List<UserProgressEntity>>
    @Query("select * from vocab_progress") fun vocabProgress(): Flow<List<VocabProgressEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveLessonProgress(item: UserProgressEntity)
    @Query("select * from vocab_progress where vocabId=:id") suspend fun vocabProgress(id: String): VocabProgressEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveVocabProgress(item: VocabProgressEntity)
    @Insert suspend fun saveMistake(item: MistakeEntity)
    @Query("delete from lesson_progress") suspend fun clearLessonProgress()
    @Query("delete from vocab_progress") suspend fun clearVocabProgress()
    @Query("delete from mistakes") suspend fun clearMistakes()
}

@Database(entities = [UnitEntity::class, LessonEntity::class, ExerciseEntity::class, VocabEntity::class, UserProgressEntity::class, VocabProgressEntity::class, MistakeEntity::class], version = 1)
abstract class ArianaDatabase : RoomDatabase() {
    abstract fun dao(): ArianaDao
    companion object {
        @Volatile private var instance: ArianaDatabase? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ArianaDatabase::class.java, "ariana.db").build().also { instance = it }
        }
    }
}

data class ArianaPrefsState(val onboarding: Boolean, val sound: Boolean, val transliteration: Boolean, val dailyGoal: Int, val xp: Int, val streak: Int, val hearts: Int)

class ArianaPrefs(private val context: Context) {
    private object K {
        val onboarding = booleanPreferencesKey("onboarding")
        val level = stringPreferencesKey("level")
        val goal = intPreferencesKey("goal")
        val sound = booleanPreferencesKey("sound")
        val tr = booleanPreferencesKey("transliteration")
        val xp = intPreferencesKey("xp")
        val streak = intPreferencesKey("streak")
        val lastDay = stringPreferencesKey("last_day")
        val hearts = intPreferencesKey("hearts")
        val refill = longPreferencesKey("refill")
        val version = intPreferencesKey("content_version")
    }
    val onboardingDone = context.dataStore.data.map { it[K.onboarding] ?: false }
    val state = context.dataStore.data.map { p -> ArianaPrefsState(p[K.onboarding] ?: false, p[K.sound] ?: true, p[K.tr] ?: false, p[K.goal] ?: 10, p[K.xp] ?: 0, p[K.streak] ?: 0, p[K.hearts] ?: 5) }
    suspend fun finishOnboarding(level: String, minutes: Int) = context.dataStore.edit { it[K.onboarding] = true; it[K.level] = level; it[K.goal] = minutes; it[K.hearts] = 5 }
    suspend fun setContentVersion(v: Int) = context.dataStore.edit { it[K.version] = v }
    suspend fun contentVersion() = context.dataStore.data.first()[K.version] ?: 0
    suspend fun addXp(xp: Int) = context.dataStore.edit { it[K.xp] = (it[K.xp] ?: 0) + xp }
    suspend fun markActiveToday() = context.dataStore.edit {
        val today = LocalDate.now().toString()
        val last = it[K.lastDay]
        if (last != today) it[K.streak] = if (last == LocalDate.now().minusDays(1).toString()) (it[K.streak] ?: 0) + 1 else 1
        it[K.lastDay] = today
    }
    suspend fun loseHeart() = context.dataStore.edit { it[K.hearts] = max(0, (it[K.hearts] ?: 5) - 1) }
    suspend fun refillHeartsIfNeeded() = context.dataStore.edit {
        val now = System.currentTimeMillis(); val last = it[K.refill] ?: now; val current = it[K.hearts] ?: 5
        val gained = ((now - last) / (4 * 60 * 60 * 1000)).toInt()
        if (gained > 0 && current < 5) { it[K.hearts] = min(5, current + gained); it[K.refill] = now }
    }
    suspend fun setSound(v: Boolean) = context.dataStore.edit { it[K.sound] = v }
    suspend fun setTransliteration(v: Boolean) = context.dataStore.edit { it[K.tr] = v }
    suspend fun resetCounters() = context.dataStore.edit { it[K.xp] = 0; it[K.streak] = 0; it[K.hearts] = 5; it[K.lastDay] = "" }
}

class ArianaRepository(private val db: ArianaDatabase, private val prefs: ArianaPrefs) {
    private val dao = db.dao()
    val units = dao.units()
    val lessons = dao.lessons()
    val progress = dao.progress()
    val vocabProgress = dao.vocabProgress()
    suspend fun lessonExercises(id: String) = dao.exercisesForLesson(id)
    suspend fun practiceExercises() = dao.practiceExercises().ifEmpty { dao.randomExercises() }
    suspend fun answer(ex: ExerciseEntity, correct: Boolean) {
        val vocabId = ex.vocabId ?: return
        val old = dao.vocabProgress(vocabId) ?: VocabProgressEntity(vocabId, 40, 0, 0, 0)
        dao.saveVocabProgress(old.copy(mastery = nextMastery(old.mastery, correct), correctCount = old.correctCount + if (correct) 1 else 0, wrongCount = old.wrongCount + if (correct) 0 else 1, lastSeen = System.currentTimeMillis()))
        if (!correct) dao.saveMistake(MistakeEntity(exerciseId = ex.id, vocabId = vocabId, whenSeen = System.currentTimeMillis()))
    }
    suspend fun finishLesson(lessonId: String, correct: Int, total: Int) {
        val xp = correct * 10 + if (correct == total) 15 else 5
        dao.saveLessonProgress(UserProgressEntity(lessonId, true, (correct * 100) / max(1, total), xp, System.currentTimeMillis()))
        prefs.addXp(xp); prefs.markActiveToday()
    }
    suspend fun resetAll() { dao.clearLessonProgress(); dao.clearVocabProgress(); dao.clearMistakes(); prefs.resetCounters() }
}

fun nextMastery(current: Int, correct: Boolean) = (current + if (correct) 10 else -12).coerceIn(0, 100)

class ContentImporter(private val context: Context, private val db: ArianaDatabase, private val prefs: ArianaPrefs) {
    suspend fun importIfNeeded() {
        if (prefs.contentVersion() >= 1 && db.dao().unitCount() > 0) return
        val root = JSONObject(context.assets.open("starter_content.json").bufferedReader().use { it.readText() })
        val units = mutableListOf<UnitEntity>(); val lessons = mutableListOf<LessonEntity>(); val vocab = mutableListOf<VocabEntity>(); val exercises = mutableListOf<ExerciseEntity>()
        root.getJSONArray("units").forEachObj { u ->
            val unitId = u.getString("id"); units += UnitEntity(unitId, u.getString("titlePs"), u.getInt("order"))
            u.getJSONArray("lessons").forEachObj { l ->
                val lessonId = l.getString("id"); lessons += LessonEntity(lessonId, unitId, l.getString("titlePs"), l.getInt("order"))
                val items = l.getJSONArray("items").mapObj { it }
                val words = items.map { it.getString("en") }
                items.forEachIndexed { index, item ->
                    val vocabId = "$lessonId-v$index"; vocab += VocabEntity(vocabId, item.getString("en"), item.getString("ps"), item.optString("tr"), item.getString("exampleEn"), item.getString("examplePs"))
                    val wrong = words.filter { it != item.getString("en") }.take(3)
                    exercises += ExerciseEntity("$lessonId-${index}a", lessonId, "choice", "د دې Pashto مانا لپاره سم English وټاکه: ${item.getString("ps")}", null, JSONArray((wrong + item.getString("en")).shuffled()).toString(), item.getString("en"), null, "سم ځواب: ${item.getString("en")}", vocabId)
                    exercises += ExerciseEntity("$lessonId-${index}b", lessonId, if (index % 4 == 0) "listen" else if (index % 4 == 1) "match" else if (index % 4 == 2) "blank" else "type", if (index % 4 == 1) "جوړې پیدا کړه: ${item.getString("ps")}" else if (index % 4 == 2) "تش ځای ډک کړه: ${item.getString("exampleEn").replace(item.getString("en"), "____", true)}" else if (index % 4 == 3) "په English یې ولیکه: ${item.getString("ps")}" else "غږ واوره او سم ځواب وټاکه", item.optString("exampleEn"), JSONArray((wrong + item.getString("en")).shuffled()).toString(), item.getString("en"), item.optString("audio", item.getString("en")), "یادونه: ${item.getString("examplePs")}", vocabId)
                }
            }
        }
        db.dao().insertUnits(units); db.dao().insertLessons(lessons); db.dao().insertVocab(vocab); db.dao().insertExercises(exercises); prefs.setContentVersion(root.getInt("version"))
    }
}

fun JSONArray.forEachObj(block: (JSONObject) -> Unit) { for (i in 0 until length()) block(getJSONObject(i)) }
fun <T> JSONArray.mapObj(block: (JSONObject) -> T): List<T> = (0 until length()).map { block(getJSONObject(it)) }
fun JSONArray.toStrings(): List<String> = (0 until length()).map { getString(it) }

class EnglishTts(context: Context) {
    private var ready = false
    private val engine = TextToSpeech(context.applicationContext) { if (it == TextToSpeech.SUCCESS) ready = true }
    fun speak(text: String, enabled: Boolean) {
        if (enabled && ready) { engine.language = Locale.US; engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ariana") }
    }
}

data class HomeUi(val units: List<UnitBlock> = emptyList(), val prefs: ArianaPrefsState = ArianaPrefsState(false, true, false, 10, 0, 0, 5))
data class UnitBlock(val unit: UnitEntity, val lessons: List<LessonTile>)
data class LessonTile(val lesson: LessonEntity, val completed: Boolean, val locked: Boolean, val score: Int)

class HomeViewModel(repo: ArianaRepository, prefs: ArianaPrefs) : ViewModel() {
    val ui = combine(repo.units, repo.lessons, repo.progress, prefs.state) { units, lessons, progress, p ->
        val done = progress.associateBy { it.lessonId }
        val ordered = lessons.sortedWith(compareBy<LessonEntity> { it.unitId }.thenBy { it.order })
        val firstOpen = ordered.firstOrNull { done[it.id]?.completed != true }?.id
        HomeUi(units.map { u -> UnitBlock(u, lessons.filter { it.unitId == u.id }.map { l -> LessonTile(l, done[l.id]?.completed == true, l.id != firstOpen && done[l.id]?.completed != true && ordered.indexOf(l) > ordered.indexOfFirst { it.id == firstOpen }, done[l.id]?.score ?: 0) }) }, p)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUi())
    init { viewModelScope.launch { prefs.refillHeartsIfNeeded() } }
}

open class LessonViewModel(private val lessonId: String, private val repo: ArianaRepository, private val prefs: ArianaPrefs, private val tts: EnglishTts) : ViewModel() {
    protected val mutableUi = MutableStateFlow(LessonUi(loading = true))
    val ui: StateFlow<LessonUi> = mutableUi
    init { viewModelScope.launch { mutableUi.value = LessonUi(exercises = loadExercises()) } }
    protected open suspend fun loadExercises(): List<ExerciseEntity> = repo.lessonExercises(lessonId)
    fun submit(input: String) = viewModelScope.launch {
        val s = mutableUi.value; val ex = s.current ?: return@launch
        val correct = input.trim().equals(ex.answerJson.trim(), ignoreCase = true)
        repo.answer(ex, correct); if (correct) prefs.addXp(10) else prefs.loseHeart()
        mutableUi.value = s.copy(answered = true, lastCorrect = correct, selected = input, correctCount = s.correctCount + if (correct) 1 else 0, mistakes = if (correct) s.mistakes else s.mistakes + ex)
    }
    fun next() = viewModelScope.launch {
        val s = mutableUi.value
        if (s.index >= s.exercises.lastIndex) {
            if (lessonId != "practice") repo.finishLesson(lessonId, s.correctCount, s.exercises.size) else prefs.markActiveToday()
            mutableUi.value = s.copy(done = true)
        }
        else mutableUi.value = s.copy(index = s.index + 1, answered = false, selected = "", lastCorrect = null)
    }
    fun speak() { val s = mutableUi.value; val ex = s.current ?: return; viewModelScope.launch { tts.speak(ex.audioTextEn ?: ex.answerJson, prefs.state.first().sound) } }
}

class PracticeViewModel(private val practiceRepo: ArianaRepository, prefs: ArianaPrefs, tts: EnglishTts) : LessonViewModel("practice", practiceRepo, prefs, tts) {
    override suspend fun loadExercises(): List<ExerciseEntity> = practiceRepo.practiceExercises()
}

data class LessonUi(val loading: Boolean = false, val exercises: List<ExerciseEntity> = emptyList(), val index: Int = 0, val answered: Boolean = false, val lastCorrect: Boolean? = null, val selected: String = "", val correctCount: Int = 0, val done: Boolean = false, val mistakes: List<ExerciseEntity> = emptyList()) {
    val current get() = exercises.getOrNull(index)
}

class ProfileViewModel(repo: ArianaRepository, prefs: ArianaPrefs) : ViewModel() {
    val ui = combine(repo.progress, repo.vocabProgress, prefs.state) { lp, vp, p -> ProfileUi(p.xp, p.streak, lp.count { it.completed }, vp.count { it.mastery >= 80 }, p.hearts) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProfileUi())
}
data class ProfileUi(val xp: Int = 0, val streak: Int = 0, val lessons: Int = 0, val mastered: Int = 0, val hearts: Int = 5)

class SettingsViewModel(private val repo: ArianaRepository, private val prefs: ArianaPrefs) : ViewModel() {
    val ui = prefs.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ArianaPrefsState(false, true, false, 10, 0, 0, 5))
    fun sound(v: Boolean) = viewModelScope.launch { prefs.setSound(v) }
    fun transliteration(v: Boolean) = viewModelScope.launch { prefs.setTransliteration(v) }
    fun reset() = viewModelScope.launch { repo.resetAll() }
}

@Composable fun SplashScreen() = Box(Modifier.fillMaxSize().background(Cream), contentAlignment = Alignment.Center) { Text("Ariana", style = MaterialTheme.typography.displaySmall, color = Green, fontWeight = FontWeight.Bold) }

@Composable
fun OnboardingScreen(done: (String, Int) -> Unit) {
    var level by remember { mutableStateOf("پیل کونکی") }; var goal by remember { mutableIntStateOf(10) }
    Column(Modifier.fillMaxSize().background(Cream).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("اریانا ته ښه راغلاست", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Green)
        Text("دلته به په ساده Pashto لارښوونو سره English زده کوې. درسونه لنډ دي، بې انټرنېټه کار کوي، او هره ورځ لږ تمرین بس دی.", Modifier.padding(vertical = 16.dp), textAlign = TextAlign.Start)
        Text("ستا کچه", fontWeight = FontWeight.Bold); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("پیل کونکی", "لږ څه پوهېږم", "منځنی").forEach { ChoiceChip(it, level == it) { level = it } } }
        Spacer(Modifier.height(16.dp)); Text("ورځنی هدف", fontWeight = FontWeight.Bold); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(5, 10, 15).forEach { ChoiceChip("$it دقیقې", goal == it) { goal = it } } }
        Button(onClick = { done(level, goal) }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(52.dp)) { Text("پیل یې کړه") }
    }
}

@Composable fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) = Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = if (selected) Green else Color.White, border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Green else Color(0xFFDAD4C8))) { Text(text, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), color = if (selected) Color.White else Ink) }

@Composable
fun HomeScreen(vm: HomeViewModel, onLesson: (String) -> Unit, onPractice: () -> Unit, onProfile: () -> Unit, onSettings: () -> Unit) {
    val ui by vm.ui.collectAsState()
    Scaffold(containerColor = Cream, bottomBar = { BottomNav(onPractice, onProfile, onSettings) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Header("زده کړه", "XP: ${ui.prefs.xp}   زړونه: ${ui.prefs.hearts}   ورځې: ${ui.prefs.streak}") }
            item { Button(onClick = { ui.units.flatMap { it.lessons }.firstOrNull { !it.locked && !it.completed }?.let { onLesson(it.lesson.id) } }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("ادامه ورکړه") } }
            ui.units.forEach { block ->
                item { Text(block.unit.titlePs, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Green) }
                items(block.lessons) { tile -> LessonRow(tile, onLesson) }
            }
        }
    }
}

@Composable fun Header(title: String, sub: String) { Column { Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(sub, color = Color.DarkGray) } }

@Composable
fun LessonRow(tile: LessonTile, onClick: (String) -> Unit) {
    val color = when { tile.completed -> Green; tile.locked -> Color(0xFFCAC3B6); else -> Gold }
    Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).clickable(enabled = !tile.locked) { onClick(tile.lesson.id) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).background(color, CircleShape), contentAlignment = Alignment.Center) { Text(if (tile.completed) "✓" else tile.lesson.order.toString(), color = Color.White, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(tile.lesson.titlePs, fontWeight = FontWeight.Bold); Text(if (tile.locked) "بند دی" else if (tile.completed) "بشپړ شو - ${tile.score}%" else "چمتو دی", color = Color.Gray) }
    }
}

@Composable fun BottomNav(onPractice: () -> Unit, onProfile: () -> Unit, onSettings: () -> Unit) {
    NavigationBar(containerColor = Color.White) {
        NavigationBarItem(selected = false, onClick = onPractice, icon = { Text("تمرین") }, label = { Text("تمرین") })
        NavigationBarItem(selected = false, onClick = onProfile, icon = { Text("زه") }, label = { Text("پروفایل") })
        NavigationBarItem(selected = false, onClick = onSettings, icon = { Text("⚙") }, label = { Text("امستنې") })
    }
}

@Composable
fun LessonScreen(vm: LessonViewModel, onDone: () -> Unit, practiceMode: Boolean = false) {
    val ui by vm.ui.collectAsState()
    if (ui.done) { ResultsScreen(ui, onDone); return }
    val ex = ui.current
    if (ui.loading || ex == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("درس چمتو کېږي...") }; return }
    Column(Modifier.fillMaxSize().background(Cream).padding(18.dp)) {
        LinearProgressIndicator(progress = { (ui.index + 1).toFloat() / max(1, ui.exercises.size) }, modifier = Modifier.fillMaxWidth())
        Text(if (practiceMode) "تمرین" else "درس", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        ExerciseCard(ex, ui, speak = vm::speak, submit = vm::submit)
        Spacer(Modifier.weight(1f))
        if (ui.answered) Feedback(ui.lastCorrect == true, ex.explanationPs)
        Button(onClick = { if (ui.answered) vm.next() }, enabled = ui.answered, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(if (ui.index == ui.exercises.lastIndex) "پایله وګوره" else "بل سوال") }
    }
}

@Composable
fun ExerciseCard(ex: ExerciseEntity, ui: LessonUi, speak: () -> Unit, submit: (String) -> Unit) {
    var typed by remember(ex.id) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(top = 18.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(16.dp)) {
        if (ex.type == "listen") Button(onClick = speak) { Text("غږ واورئ") }
        Text(ex.promptPs, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 10.dp))
        when (ex.type) {
            "type" -> {
                OutlinedTextField(value = typed, onValueChange = { typed = it }, enabled = !ui.answered, label = { Text("English ځواب") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None), modifier = Modifier.fillMaxWidth())
                Button(onClick = { submit(typed) }, enabled = typed.isNotBlank() && !ui.answered, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("ځواب وګوره") }
            }
            "match" -> {
                Text("Pashto: ${ex.promptPs.substringAfter(":").trim()}", color = Color.Gray)
                Options(ex, ui, submit)
            }
            else -> Options(ex, ui, submit)
        }
    }
}

@Composable fun Options(ex: ExerciseEntity, ui: LessonUi, submit: (String) -> Unit) {
    JSONArray(ex.optionsJson).toStrings().forEach { option ->
        val chosen = ui.selected == option
        val good = ui.answered && option == ex.answerJson
        val border = if (good) Green else if (chosen && ui.lastCorrect == false) Rose else Color(0xFFDAD4C8)
        Text(option, Modifier.fillMaxWidth().padding(vertical = 5.dp).border(1.dp, border, RoundedCornerShape(8.dp)).background(if (good) Color(0xFFE5F4EF) else Color.White, RoundedCornerShape(8.dp)).clickable(enabled = !ui.answered) { submit(option) }.padding(14.dp), textAlign = TextAlign.Start)
    }
}

@Composable fun Feedback(correct: Boolean, text: String) = Surface(color = if (correct) Color(0xFFE5F4EF) else Color(0xFFFFE9ED), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) { Text(if (correct) "سم دی! $text" else "بیا هڅه ښه ده. $text", Modifier.padding(14.dp), color = if (correct) Green else Rose, fontWeight = FontWeight.Bold) }

@Composable
fun ResultsScreen(ui: LessonUi, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Cream).padding(22.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("درس بشپړ شو", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Green)
        Text("سم ځوابونه: ${ui.correctCount}/${ui.exercises.size}", Modifier.padding(12.dp))
        Text("ګټلي XP: ${ui.correctCount * 10 + 5}", fontWeight = FontWeight.Bold)
        if (ui.mistakes.isNotEmpty()) { Text("د بیاکتنې لپاره تېروتنې", Modifier.padding(top = 20.dp), fontWeight = FontWeight.Bold); ui.mistakes.forEach { Text("${it.promptPs}\nسم: ${it.answerJson}", Modifier.fillMaxWidth().padding(8.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(10.dp)) } }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(54.dp)) { Text("کور ته لاړ شه") }
    }
}

@Composable
fun ProfileScreen(vm: ProfileViewModel, back: () -> Unit) {
    val ui by vm.ui.collectAsState()
    SimplePage("پروفایل", back) {
        Stat("ټول XP", ui.xp.toString()); Stat("پرله پسې ورځې", ui.streak.toString()); Stat("بشپړ درسونه", ui.lessons.toString()); Stat("زده شوي لغتونه", ui.mastered.toString()); Stat("زړونه", ui.hearts.toString())
    }
}

@Composable fun Stat(label: String, value: String) = Row(Modifier.fillMaxWidth().padding(vertical = 7.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(label); Text(value, fontWeight = FontWeight.Bold, color = Green) }

@Composable
fun SettingsScreen(vm: SettingsViewModel, back: () -> Unit) {
    val ui by vm.ui.collectAsState(); var confirm by remember { mutableStateOf(false) }
    SimplePage("امستنې", back) {
        SettingSwitch("غږ فعال وي", ui.sound, vm::sound)
        SettingSwitch("Pashto تلفظ په لاتین حروفو ښکاره کړه", ui.transliteration, vm::transliteration)
        OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) { Text("پرمختګ له سره پیل کړه") }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("ډاډه یې؟") }, text = { Text("ستاسو XP، زړونه، ورځې او درس پرمختګ پاکېږي.") }, confirmButton = { TextButton(onClick = { vm.reset(); confirm = false }) { Text("هو، پاک یې کړه") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("نه") } })
}

@Composable fun SettingSwitch(label: String, checked: Boolean, change: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text(label, Modifier.weight(1f)); Switch(checked, change) }

@Composable
fun SimplePage(title: String, back: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Cream).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = back) { Text("بېرته") }; Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), content = content)
    }
}
