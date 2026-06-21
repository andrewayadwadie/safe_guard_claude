package com.safeguard.parentalcontrol.ml

import java.util.regex.Pattern

/**
 * Data class for custom blacklist word configuration
 */
data class CustomBlacklistWord(
    val word: String,
    val category: String = "custom",
    val caseSensitive: Boolean = false,
    val wholeWordOnly: Boolean = true
)

/**
 * Stage 1: Fast regex/keyword-based text analysis.
 *
 * This runs BEFORE the AI model to catch obvious cases quickly.
 * If this detects something, we skip the AI model entirely (saves battery).
 *
 * Features:
 * - Pre-compiled regex patterns for speed (compiled once at init)
 * - Handles common evasion techniques (leetspeak, spacing, symbols)
 * - O(n) complexity for keyword matching
 * - Zero external dependencies
 * - Support for parent-defined whitelist/blacklist words
 */
class TextPatternMatcher {

    // ==================== CUSTOM WORD LISTS ====================
    // Parent-defined words that override default behavior
    private var customWhitelist: Set<String> = emptySet()
    private var customBlacklist: List<CustomBlacklistWord> = emptyList()
    private var compiledBlacklistPatterns: List<Pair<Pattern, CustomBlacklistWord>> = emptyList()

    // ==================== PROFANITY PATTERNS ====================
    // Handles: f*ck, f u c k, f.u.c.k, fu©k, phuck, etc.
    private val profanityPatterns = listOf(
        // F-word variations
        Pattern.compile("\\b(f+[\\W_]*u+[\\W_]*c+[\\W_]*k+|ph+[\\W_]*u+[\\W_]*c+[\\W_]*k+)\\w*\\b", Pattern.CASE_INSENSITIVE),
        // S-word variations
        Pattern.compile("\\b(s+[\\W_]*h+[\\W_]*[i1!]+[\\W_]*t+)\\w*\\b", Pattern.CASE_INSENSITIVE),
        // B-word variations
        Pattern.compile("\\b(b+[\\W_]*[i1!]+[\\W_]*t+[\\W_]*c+[\\W_]*h+)\\w*\\b", Pattern.CASE_INSENSITIVE),
        // A-word variations
        Pattern.compile("\\b(a+[\\W_]*s+[\\W_]*s+|@ss)\\w*\\b", Pattern.CASE_INSENSITIVE),
        // D-word variations
        Pattern.compile("\\b(d+[\\W_]*[a@]+[\\W_]*m+[\\W_]*n+)\\b", Pattern.CASE_INSENSITIVE),
        // C-word (offensive)
        Pattern.compile("\\b(c+[\\W_]*u+[\\W_]*n+[\\W_]*t+)\\b", Pattern.CASE_INSENSITIVE),
        // N-word (racial slur)
        Pattern.compile("\\b(n+[\\W_]*[i1!]+[\\W_]*g+[\\W_]*g+[\\W_]*[ae@]+[\\W_]*r*)\\b", Pattern.CASE_INSENSITIVE),
        // Other common profanity
        Pattern.compile("\\b(bastard|whore|slut|d[i1!]ck|c[o0]ck|penis|vagina)\\b", Pattern.CASE_INSENSITIVE),
    )

    // ==================== SELF-HARM PATTERNS (HIGH PRIORITY) ====================
    private val selfHarmPatterns = listOf(
        // "kill myself", "killmyself", "kill my self"
        Pattern.compile("\\b(kill\\s*(my)?\\s*self|killmyself|suicide|suicidal)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(want\\s*to\\s*die|wanna\\s*die|ready\\s*to\\s*die)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(end\\s*(my)?\\s*life|take\\s*my\\s*life)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(self[\\W_]*harm|cut\\s*(my)?\\s*self|cutting\\s*myself)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(hurt\\s*(my)?\\s*self|hurting\\s*myself)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(no\\s*reason\\s*to\\s*live|not\\s*worth\\s*living)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(better\\s*off\\s*dead|wish\\s*i\\s*was\\s*dead)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(overdose|od'?ing|pills\\s*to\\s*die)\\b", Pattern.CASE_INSENSITIVE),
    )

    // ==================== BULLYING PATTERNS ====================
    private val bullyingPatterns = listOf(
        // "kill yourself", "killyourself", "kill your self", "kys", "go die"
        Pattern.compile("\\b(kill\\s*your\\s*self|killyourself|kill\\s*yourself|kys|go\\s*die)\\b", Pattern.CASE_INSENSITIVE),
        // "you should kill yourself" - matches "should kill yourself/your self"
        Pattern.compile("\\b(should\\s*(just\\s*)?kill\\s*(yourself|your\\s*self))\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(nobody\\s*(likes|loves|wants)\\s*you)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(you('re|\\s*are)?\\s*(worthless|pathetic|disgusting|ugly|fat|stupid|dumb|retard))\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(hate\\s*you|i\\s*hate\\s*you)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(loser|freak|weirdo|creep|loner)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(no\\s*one\\s*cares\\s*(about\\s*you)?)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(you\\s*should\\s*(just\\s*)?(die|disappear|leave))\\b", Pattern.CASE_INSENSITIVE),
    )

    // ==================== VIOLENCE PATTERNS ====================
    private val violencePatterns = listOf(
        Pattern.compile("\\b(i('ll|\\s*will)?\\s*kill\\s*(you|him|her|them))\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(murder|shoot|stab|strangle|choke)\\s*(you|him|her|them)?\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(beat\\s*(you|the\\s*shit|him|her)\\s*(up)?)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(bomb|blow\\s*up|terrorist|attack)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(weapon|gun|knife|sword|machete)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(hurt\\s*you|gonna\\s*hurt|make\\s*you\\s*bleed)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(fight\\s*(me|you)|wanna\\s*fight)\\b", Pattern.CASE_INSENSITIVE),
    )

    // ==================== DRUGS/ALCOHOL PATTERNS ====================
    private val drugsPatterns = listOf(
        Pattern.compile("\\b(weed|marijuana|cannabis|pot|420|blunt|joint|bong)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(cocaine|coke|crack|heroin|meth|mdma|ecstasy|molly)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(xanax|percocet|oxy|adderall|lean|codeine)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(get\\s*(high|stoned|wasted|drunk|fucked\\s*up))\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(smoke\\s*weed|pop\\s*pills|do\\s*drugs)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(dealer|plug|score\\s*(some|drugs))\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(drunk|wasted|hammered|shitfaced|trashed)\\b", Pattern.CASE_INSENSITIVE),
    )

    // ==================== SEXUAL CONTENT PATTERNS ====================
    private val sexualPatterns = listOf(
        Pattern.compile("\\b(porn|xxx|nsfw|hentai|rule34)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(nude|naked|nudes|topless|bottomless)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(sex|fuck\\s*me|wanna\\s*fuck|let'?s\\s*fuck)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(onlyfans|fansly|chaturbate|pornhub)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(hookup|hook\\s*up|fwb|friends\\s*with\\s*benefits)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(send\\s*nudes|dic?k\\s*pic|tit\\s*pic)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(horny|turned\\s*on|so\\s*wet)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(sexy\\s*(pics?|photos?)|hot\\s*pics?)\\b", Pattern.CASE_INSENSITIVE),
    )

    // ==================== PREDATOR/GROOMING PATTERNS ====================
    private val predatorPatterns = listOf(
        Pattern.compile("\\b(how\\s*old\\s*(are\\s*you|r\\s*u))\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(send\\s*(me\\s*)?(pic|photo|selfie)s?)\\b", Pattern.CASE_INSENSITIVE),
        // "don't tell your parent/parents/mom/dad/anyone", "keep it secret"
        Pattern.compile("\\b(keep\\s*(this|it)\\s*secret|don'?t\\s*tell\\s*(anyone|your\\s*parents?|your\\s*(mom|dad|mum|mother|father)))\\b", Pattern.CASE_INSENSITIVE),
        // "our secret", "this is our secret", "between us"
        Pattern.compile("\\b(our\\s*little\\s*secret|this\\s*(is|stays)\\s*between\\s*us|just\\s*between\\s*us)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(meet\\s*(up|me)|let'?s\\s*meet|wanna\\s*meet)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(where\\s*(do\\s*you|u)\\s*live|your\\s*address)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(alone\\s*(at\\s*)?home|home\\s*alone|parents?\\s*(gone|away|not\\s*home))\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(video\\s*call|turn\\s*on\\s*(your)?\\s*camera)\\b", Pattern.CASE_INSENSITIVE),
        // Age-related grooming questions
        Pattern.compile("\\b(what\\s*grade|what\\s*school|which\\s*school)\\b", Pattern.CASE_INSENSITIVE),
    )

    // ==================== HARMFUL BEHAVIOR ENCOURAGEMENT PATTERNS ====================
    // Catches phrases that encourage harmful/illegal actions
    private val harmfulBehaviorPatterns = listOf(
        // Stealing / Theft
        Pattern.compile("\\b(you\\s*(should|need\\s*to|gotta|have\\s*to)\\s*steal)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(let'?s\\s*steal|we\\s*should\\s*steal|go\\s*steal)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(take\\s*money\\s*from\\s*(your|the|mom|dad|parent))\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(steal\\s*(money|cash|from))\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(shoplift|shoplifting|five\\s*finger\\s*discount)\\b", Pattern.CASE_INSENSITIVE),
        // Running away
        Pattern.compile("\\b(run\\s*away\\s*from\\s*home|leave\\s*home|escape\\s*from\\s*(your\\s*)?parents?)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(you\\s*should\\s*run\\s*away|let'?s\\s*run\\s*away)\\b", Pattern.CASE_INSENSITIVE),
        // Skipping school / Disobedience
        Pattern.compile("\\b(skip\\s*school|ditch\\s*(school|class)|cut\\s*class)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(don'?t\\s*(go\\s*to|listen\\s*to)\\s*(school|class|your\\s*parents?|your\\s*(mom|dad)))\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(your\\s*parents?\\s*(don'?t|are\\s*wrong|are\\s*stupid))\\b", Pattern.CASE_INSENSITIVE),
        // Lying to parents
        Pattern.compile("\\b(lie\\s*to\\s*(your\\s*)?(parents?|mom|dad|family))\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(tell\\s*(your\\s*)?(parents?|mom|dad)\\s*(you'?re|that\\s*you))\\b", Pattern.CASE_INSENSITIVE),
        // Sneaking out
        Pattern.compile("\\b(sneak\\s*out|sneaking\\s*out|sneak\\s*away)\\b", Pattern.CASE_INSENSITIVE),
        // Vandalism
        Pattern.compile("\\b(vandalize|vandalism|break\\s*(into|windows)|graffiti|tag\\s*(the|a)\\s*wall)\\b", Pattern.CASE_INSENSITIVE),
        // Cheating
        Pattern.compile("\\b(cheat\\s*on\\s*(the|your)\\s*(test|exam)|copy\\s*(my|the)\\s*answers?)\\b", Pattern.CASE_INSENSITIVE),
    )

    // ==================== ARABIC PATTERNS (Egyptian Dialect Focus) ====================

    // Arabic Profanity - كلمات بذيئة
    private val arabicProfanityPatterns = listOf(
        // Very vulgar insults
        Pattern.compile("(كس\\s*[اأ]م|كسمك|كس\\s*اختك)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(ابن\\s*ال(شرموط[ةه]|متناك[ةه]|وسخ[ةه]|قحب[ةه]))", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(يا\\s*(خول|عرص|معرص|متناك|ديوث))", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(يا\\s*(زبال[ةه]|حيوان|كلب|حمار|تيس|ثور))", Pattern.CASE_INSENSITIVE),
        // Body parts (vulgar)
        Pattern.compile("(طيز|زب|زبر|بز[اأ]ز)", Pattern.CASE_INSENSITIVE),
        // Vulgar words
        Pattern.compile("(شرموط[ةه]|قحب[ةه]|متناك[ةه]|منيوك[ةه])", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(عرص[ةه]|خول[ةه]|ديوث)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(ينيك|نيك|انيك|تنيك|منايك)", Pattern.CASE_INSENSITIVE),
        // Common Egyptian vulgar
        Pattern.compile("(يلعن\\s*(ابو|دين|ام)|العن\\s*ابو)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(ولاد?\\s*ال(وسخ[ةه]|شرموط[ةه]))", Pattern.CASE_INSENSITIVE),
    )

    // Arabic Self-Harm - إيذاء النفس
    private val arabicSelfHarmPatterns = listOf(
        // Suicide-related
        Pattern.compile("([اأ]نتحر|انتحار|هنتحر|عايز\\s*[اأ]نتحر)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(ه[اأ]قتل\\s*نفسي|عايز\\s*[اأ]قتل\\s*نفسي)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(عايز\\s*[اأ]موت|مش\\s*عايز\\s*[اأ]عيش|نفسي\\s*[اأ]موت)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(الحيا[ةه]\\s*ملهاش\\s*معنى|مفيش\\s*[اأ]مل)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(ه[اأ][اأذ]ي\\s*نفسي|ب[اأ][اأذ]ي\\s*نفسي)", Pattern.CASE_INSENSITIVE),
        // Hopelessness
        Pattern.compile("(مش\\s*قادر\\s*[اأ]كمل|خلاص\\s*مش\\s*قادر)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(الدنيا\\s*وحش[ةه]|مفيش\\s*فايد[ةه])", Pattern.CASE_INSENSITIVE),
        Pattern.compile("([اأ]حسن\\s*لو\\s*مت|يا\\s*ريت\\s*[اأ]موت)", Pattern.CASE_INSENSITIVE),
    )

    // Arabic Bullying - التنمر
    private val arabicBullyingPatterns = listOf(
        // Intelligence insults
        Pattern.compile("(يا\\s*(غبي|[اأ]حمق|عبيط|هبل[ةه]|متخلف|[اأ]هبل))", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(انت[ةه]?\\s*(غبي|[اأ]حمق|فاشل|تافه|عبيط))", Pattern.CASE_INSENSITIVE),
        // Social exclusion
        Pattern.compile("(محدش\\s*بيحبك|مفيش\\s*حد\\s*بيحبك)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(محدش\\s*عايزك|روح\\s*من\\s*هنا)", Pattern.CASE_INSENSITIVE),
        // Appearance insults
        Pattern.compile("(يا\\s*(سمين|تخين|وحش|قبيح|عيل))", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(انت[ةه]?\\s*(وحش[ةه]|قبيح[ةه]|مقرف[ةه]))", Pattern.CASE_INSENSITIVE),
        // Death wishes - telling someone to die
        Pattern.compile("(روح\\s*موت|[اأ]قتل\\s*نفسك|موت\\s*بقى)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(يا\\s*ريتك\\s*تموت|نفسي\\s*تموت)", Pattern.CASE_INSENSITIVE),
        // General insults
        Pattern.compile("(انت[ةه]?\\s*(فاشل[ةه]|تافه[ةه]|حقير[ةه]|زباله[ةه]))", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(يا\\s*(لوزر|فاشل|نكر[ةه]|مسخر[ةه]))", Pattern.CASE_INSENSITIVE),
    )

    // Arabic Violence - العنف
    private val arabicViolencePatterns = listOf(
        // Death threats
        Pattern.compile("(ه[اأ]قتلك|هموتك|هخلص\\s*عليك)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(هدبحك|هقطعك|هكسرك)", Pattern.CASE_INSENSITIVE),
        // Physical violence
        Pattern.compile("(هضربك|هنضربك|هوريك)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(هكسر\\s*(وشك|راسك|ضهرك))", Pattern.CASE_INSENSITIVE),
        // Weapons
        Pattern.compile("(سلاح|مسدس|سكين[ةه]?|مطو[ةه])", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(قنبل[ةه]|متفجرات|ارهاب)", Pattern.CASE_INSENSITIVE),
        // Violence verbs
        Pattern.compile("(دبح|طعن|قتل|اغتيال)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(ضرب|خنق|حرق)", Pattern.CASE_INSENSITIVE),
    )

    // Arabic Sexual Content - محتوى جنسي
    private val arabicSexualPatterns = listOf(
        // Explicit terms
        Pattern.compile("(بورن|سكس|اباحي[ةه]?|xxxx?)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(صور\\s*(عري|عريان[ةه]?|سكس|نود))", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(فيديو\\s*(سكس|اباحي|عري))", Pattern.CASE_INSENSITIVE),
        // Body parts (sexual context)
        Pattern.compile("(عريان[ةه]?|بدون\\s*هدوم|قالع[ةه]?)", Pattern.CASE_INSENSITIVE),
        // Sexual requests
        Pattern.compile("(ابعت[يه]?\\s*(صور|نود)|نفسي\\s*[اأ]شوفك)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(عايز\\s*[اأ]شوف\\s*جسمك|ورني\\s*جسمك)", Pattern.CASE_INSENSITIVE),
        // Sexual feelings
        Pattern.compile("(هايج[ةه]?|نشوان[ةه]?|مثار[ةه]?)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(عايز[ةه]?\\s*[اأ]نيك|تعال[ي]?\\s*ننيك)", Pattern.CASE_INSENSITIVE),
    )

    // Arabic Drugs - المخدرات
    private val arabicDrugsPatterns = listOf(
        // Cannabis
        Pattern.compile("(حشيش|بانجو|ماريجوانا|غاز)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(جوينت|بونج|سجار[ةه]\\s*حشيش)", Pattern.CASE_INSENSITIVE),
        // Pills and hard drugs
        Pattern.compile("(ترامادول|كبتاجون|هيروين|كوكايين)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(حبوب|برشام|مخدرات)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(كيتامين|شابو|ايس|كريستال)", Pattern.CASE_INSENSITIVE),
        // Being high
        Pattern.compile("(سطلان|مسطول|طاير|فايق)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(منتشي|هاي|ستون[دت])", Pattern.CASE_INSENSITIVE),
        // Alcohol
        Pattern.compile("(خمر[ةه]?|كحول|بير[ةه]|ويسكي|فودكا)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(سكران[ةه]?|مخمور[ةه]?|شارب)", Pattern.CASE_INSENSITIVE),
        // Drug dealing
        Pattern.compile("(تاجر\\s*مخدرات|ديلر|بياع\\s*بودر[ةه])", Pattern.CASE_INSENSITIVE),
    )

    // Arabic Predator/Grooming - التحرش والاستدراج
    private val arabicPredatorPatterns = listOf(
        // Asking for photos
        Pattern.compile("(ابعت[يه]?\\s*(لي)?\\s*صور(تك|[ةه])?)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(عايز\\s*[اأ]شوف\\s*صورتك|ورين?ي\\s*صورتك)", Pattern.CASE_INSENSITIVE),
        // Secrecy
        Pattern.compile("(متقول[يش]+\\s*ل?حد|سر\\s*بين[نا]+)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(ده\\s*سرنا|خلي[ها]?\\s*سر|بيننا\\s*بس)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(متقول[يش]+\\s*ل?(ماما|بابا|[اأ]هلك))", Pattern.CASE_INSENSITIVE),
        // Location questions
        Pattern.compile("(انت[ةه]?\\s*فين|فين\\s*بيتك|عنوانك\\s*[اأ]يه)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(لوحدك\\s*في\\s*البيت|[اأ]هلك\\s*مش\\s*موجودين)", Pattern.CASE_INSENSITIVE),
        // Meeting requests
        Pattern.compile("(تعال[ي]?\\s*نتقابل|عايز\\s*[اأ]شوفك|نتقابل\\s*فين)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(ممكن\\s*نتقابل|عايز[ةه]?\\s*[اأ]قابلك)", Pattern.CASE_INSENSITIVE),
        // Age-related grooming
        Pattern.compile("(انت[ةه]?\\s*كبرت[ي]?|انت[ةه]?\\s*(حلو[ةه]|جميل[ةه])\\s*[اأ]وي)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(عمرك\\s*كام|انت[ةه]?\\s*في\\s*[اأ]ي\\s*سن[ةه]?)", Pattern.CASE_INSENSITIVE),
        // Video call requests
        Pattern.compile("(فتح[ي]?\\s*الكاميرا|تعال[ي]?\\s*فيديو\\s*كول)", Pattern.CASE_INSENSITIVE),
    )

    // Arabic Harmful Behavior - سلوكيات ضارة
    private val arabicHarmfulBehaviorPatterns = listOf(
        // Stealing
        Pattern.compile("(لازم\\s*تسرق|يلا\\s*نسرق|اسرق[ي]?\\s*(فلوس|من))", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(خد\\s*فلوس\\s*من\\s*(ماما|بابا|[اأ]هلك))", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(اسرق[ي]?\\s*من\\s*(البيت|الشنط[ةه]))", Pattern.CASE_INSENSITIVE),
        // Running away
        Pattern.compile("(اهرب[ي]?\\s*من\\s*البيت|سيب[ي]?\\s*البيت)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(يلا\\s*نهرب|لازم\\s*تهرب[ي]?)", Pattern.CASE_INSENSITIVE),
        // Skipping school
        Pattern.compile("(متروح[يش]+\\s*المدرس[ةه]|سيب[ي]?\\s*المدرس[ةه])", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(اهرب[ي]?\\s*من\\s*المدرس[ةه]|يلا\\s*نفوت\\s*المدرس[ةه])", Pattern.CASE_INSENSITIVE),
        // Lying to parents
        Pattern.compile("(اكذب[ي]?\\s*على\\s*(ماما|بابا|[اأ]هلك))", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(قول[ي]?\\s*ل?(ماما|بابا)\\s*انك)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(متقول[يش]+\\s*ل?[اأ]هلك\\s*(الحقيق[ةه]|[اأ]يه\\s*حصل))", Pattern.CASE_INSENSITIVE),
        // Sneaking out
        Pattern.compile("(اخرج[ي]?\\s*من\\s*غير\\s*ما\\s*حد\\s*يعرف)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(اتسلل[ي]?|اخرج[ي]?\\s*بليل)", Pattern.CASE_INSENSITIVE),
        // Disobeying parents
        Pattern.compile("(متسمع[يش]+\\s*(كلام\\s*)?(ماما|بابا|[اأ]هلك))", Pattern.CASE_INSENSITIVE),
        Pattern.compile("((ماما|بابا|[اأ]هلك)\\s*(غلط|مش\\s*فاهم|مش\\s*صح))", Pattern.CASE_INSENSITIVE),
        // Cheating
        Pattern.compile("((اغش[ي]?|غش[ي]?)\\s*في\\s*(الامتحان|الاختبار))", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(انقل[ي]?\\s*(من[ي]?|الاجاب[ةه]))", Pattern.CASE_INSENSITIVE),
    )

    // ==================== EVASION TECHNIQUE HANDLING ====================

    // Zero-width and invisible characters to strip
    private val zeroWidthChars = setOf(
        '\u200B', // Zero-width space
        '\u200C', // Zero-width non-joiner
        '\u200D', // Zero-width joiner
        '\u200E', // Left-to-right mark
        '\u200F', // Right-to-left mark
        '\uFEFF', // Zero-width no-break space (BOM)
        '\u00AD', // Soft hyphen
        '\u034F', // Combining grapheme joiner
        '\u2060', // Word joiner
        '\u2061', // Function application
        '\u2062', // Invisible times
        '\u2063', // Invisible separator
        '\u2064', // Invisible plus
        '\u180E', // Mongolian vowel separator
        '\u061C', // Arabic letter mark
    )

    // Combining diacritical marks range (U+0300 to U+036F)
    // These are used to add accents/marks to letters: f̷u̷c̷k̷
    private val combiningDiacriticsRegex = Regex("[\u0300-\u036F\u0489\u20D0-\u20FF\uFE20-\uFE2F]")

    // Full-width to ASCII mapping (ｆｕｃｋ → fuck)
    private val fullWidthOffset = 0xFF00 - 0x0020 // Full-width chars start at U+FF00

    // Unicode confusables / homoglyphs mapping
    // Maps look-alike characters from other scripts to ASCII
    private val homoglyphMap = mapOf(
        // Cyrillic → Latin
        'а' to 'a', 'А' to 'a',  // Cyrillic a
        'с' to 'c', 'С' to 'c',  // Cyrillic es (looks like c)
        'е' to 'e', 'Е' to 'e',  // Cyrillic ie
        'ё' to 'e', 'Ё' to 'e',  // Cyrillic io
        'о' to 'o', 'О' to 'o',  // Cyrillic o
        'р' to 'p', 'Р' to 'p',  // Cyrillic er (looks like p)
        'х' to 'x', 'Х' to 'x',  // Cyrillic ha (looks like x)
        'у' to 'y', 'У' to 'y',  // Cyrillic u (looks like y)
        'і' to 'i', 'І' to 'i',  // Ukrainian i
        'ј' to 'j', 'Ј' to 'j',  // Cyrillic je
        'ѕ' to 's', 'Ѕ' to 's',  // Cyrillic dze
        'ԁ' to 'd',              // Cyrillic komi de
        'ԛ' to 'q',              // Cyrillic qa
        'ԝ' to 'w',              // Cyrillic we
        'ᴄ' to 'c', 'ᴅ' to 'd', 'ᴇ' to 'e', 'ɢ' to 'g',
        'ʜ' to 'h', 'ɪ' to 'i', 'ᴊ' to 'j', 'ᴋ' to 'k',
        'ʟ' to 'l', 'ᴍ' to 'm', 'ɴ' to 'n', 'ᴏ' to 'o',
        'ᴘ' to 'p', 'ʀ' to 'r', 'ꜱ' to 's', 'ᴛ' to 't',
        'ᴜ' to 'u', 'ᴠ' to 'v', 'ᴡ' to 'w', 'ʏ' to 'y',
        'ᴢ' to 'z',

        // Greek → Latin
        'α' to 'a', 'Α' to 'a',  // Alpha
        'β' to 'b', 'Β' to 'b',  // Beta
        'ε' to 'e', 'Ε' to 'e',  // Epsilon
        'η' to 'n', 'Η' to 'h',  // Eta
        'ι' to 'i', 'Ι' to 'i',  // Iota
        'κ' to 'k', 'Κ' to 'k',  // Kappa
        'ν' to 'v', 'Ν' to 'n',  // Nu
        'ο' to 'o', 'Ο' to 'o',  // Omicron
        'ρ' to 'p', 'Ρ' to 'p',  // Rho (looks like p)
        'τ' to 't', 'Τ' to 't',  // Tau
        'υ' to 'u', 'Υ' to 'y',  // Upsilon
        'χ' to 'x', 'Χ' to 'x',  // Chi

        // Other confusables
        'ı' to 'i',              // Turkish dotless i
        'ł' to 'l', 'Ł' to 'l',  // Polish l with stroke
        'ø' to 'o', 'Ø' to 'o',  // Scandinavian o with stroke
        'æ' to 'a', 'Æ' to 'a',  // Ligature ae
        'œ' to 'o', 'Œ' to 'o',  // Ligature oe
        'ß' to 's',              // German sharp s
        'đ' to 'd', 'Đ' to 'd',  // D with stroke
        'ħ' to 'h', 'Ħ' to 'h',  // H with stroke
        'ŧ' to 't', 'Ŧ' to 't',  // T with stroke
        'ƒ' to 'f',              // Function symbol (looks like f)
        'ɑ' to 'a',              // Latin alpha
        'ɡ' to 'g',              // Script g
        'ʙ' to 'b',              // Small capital B
        'ℓ' to 'l',              // Script small l
        '℮' to 'e',              // Estimated symbol
        'ⅰ' to 'i', 'ⅱ' to 'i',  // Roman numerals
        'ⅲ' to 'i', 'ⅳ' to 'i',
        'ⅴ' to 'v', 'ⅵ' to 'v',
        'ⅹ' to 'x',

        // Mathematical/special symbols that look like letters
        '∂' to 'd',              // Partial differential
        '∩' to 'n',              // Intersection
        '∪' to 'u',              // Union
        '⊂' to 'c',              // Subset
        '⊃' to 'c',              // Superset
        '℃' to 'c',              // Degree Celsius
        '№' to 'n',              // Numero sign
        '™' to 't',              // Trademark
        '©' to 'c',              // Copyright
        '®' to 'r',              // Registered
    )

    // Extended leetspeak mapping (more comprehensive)
    private val leetMap = mapOf(
        // Numbers
        '0' to 'o',
        '1' to 'i',  // Can also be 'l'
        '2' to 'z',  // Less common
        '3' to 'e',
        '4' to 'a',
        '5' to 's',
        '6' to 'g',  // Or 'b'
        '7' to 't',
        '8' to 'b',  // Or 'ate'
        '9' to 'g',  // Or 'q'

        // Symbols
        '@' to 'a',
        '$' to 's',
        '!' to 'i',
        '+' to 't',
        '(' to 'c',
        ')' to 'd',  // Less common
        '[' to 'c',
        ']' to 'd',  // Less common
        '{' to 'c',
        '}' to 'd',  // Less common
        '|' to 'i',  // Or 'l'
        '\\' to 'l',
        '/' to 'l',  // Or 'i'
        '<' to 'c',
        '>' to 'd',  // Less common
        '^' to 'a',
        '*' to 'x',  // Or wildcard
        '~' to 'n',
        '¡' to 'i',
        '¢' to 'c',
        '£' to 'l',
        '€' to 'e',
        '¥' to 'y',
        '§' to 's',
        '¶' to 'p',
        '×' to 'x',
        '÷' to 'd',  // Less common

        // Accented characters → base letter
        'á' to 'a', 'à' to 'a', 'â' to 'a', 'ä' to 'a', 'ã' to 'a', 'å' to 'a', 'ą' to 'a',
        'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e', 'ę' to 'e', 'ě' to 'e',
        'í' to 'i', 'ì' to 'i', 'î' to 'i', 'ï' to 'i', 'į' to 'i',
        'ó' to 'o', 'ò' to 'o', 'ô' to 'o', 'ö' to 'o', 'õ' to 'o', 'ő' to 'o',
        'ú' to 'u', 'ù' to 'u', 'û' to 'u', 'ü' to 'u', 'ű' to 'u', 'ů' to 'u',
        'ý' to 'y', 'ÿ' to 'y',
        'ñ' to 'n', 'ń' to 'n', 'ň' to 'n',
        'ç' to 'c', 'ć' to 'c', 'č' to 'c',
        'ś' to 's', 'š' to 's', 'ş' to 's',
        'ž' to 'z', 'ź' to 'z', 'ż' to 'z',
        'ř' to 'r', 'ŕ' to 'r',
        'ť' to 't',
        'ď' to 'd',
        'ľ' to 'l', 'ĺ' to 'l',
    )

    // ==================== FALSE POSITIVE PREVENTION ====================

    // Common words that contain "bad" substrings but are innocent
    // These are checked BEFORE flagging to prevent false positives
    private val falsePositiveWords = setOf(
        // Contains "ass"
        "assignment", "assignments", "class", "classes", "classic", "classical",
        "classification", "classified", "classify", "classmate", "classmates",
        "classroom", "pass", "passed", "passing", "password", "passwords",
        "passport", "passports", "mass", "massive", "massage", "massachusetts",
        "assassin", "assault", "assemble", "assembly", "assert", "assertion",
        "assess", "assessment", "asset", "assets", "assign", "assigned",
        "assist", "assistant", "associate", "associated", "association",
        "assume", "assumed", "assumption", "assurance", "assure", "assured",
        "bass", "brass", "grass", "embassy", "embarrass", "embarrassed",
        "compass", "cassette", "casserole", "hassle", "lasso", "tassel",

        // Contains "cock"
        "cocktail", "cocktails", "cockatoo", "peacock", "hancock", "cockpit",
        "cockroach", "cocky", "gamecock", "stopcock", "woodcock", "babcock",

        // Contains "cum"
        "document", "documents", "documentation", "circumstance", "circumstances",
        "circumference", "accumulate", "accumulated", "accumulation",
        "cucumber", "encumber", "incumbent", "succumb", "spectrum",

        // Contains "dick"
        "dickens", "dickson", "dickinson", "dictionary", "dictate", "dictation",
        "edickt", "predict", "prediction", "verdict", "addict", "addicted",
        "addiction", "benedict", "contradict", "jurisdiction",

        // Contains "fag"
        "flag", "flags", "flagged", "fagged", "unflagging",

        // Contains "hell"
        "hello", "shell", "shells", "seashell", "mitchell", "michelle",
        "nutshell", "bombshell", "eggshell", "shellfish", "shelter",
        "hellenistic", "helicopter", "othello", "dwell", "farewell",

        // Contains "homo"
        "homogeneous", "homogenize", "homonym", "homophone",

        // Contains "porn" - very few legitimate words

        // Contains "sex"
        "sextant", "sextet", "sexton", "essex", "sussex", "middlesex",
        "unisex", "sexagenarian",

        // Contains "shit"
        "shiitake", "shiatsu",

        // Contains "tit"
        "title", "titles", "titled", "entitle", "entitled", "subtitle",
        "constitution", "constitutional", "constitutionally", "constitute",
        "institution", "institutional", "stitution", "restitution",
        "substitute", "substitution", "petition", "petitioner", "competition",
        "competitive", "competitor", "appetite", "repetition", "repetitive",
        "partition", "titan", "titanium", "titanic", "quantity", "quantities",
        "identity", "identities", "entity", "entities", "attitude",

        // Contains "weed"
        "seaweed", "tweed", "tweeds",

        // Contains "drug"
        "shrug", "shrugged",

        // Contains "rape"
        "grape", "grapes", "grapefruit", "drape", "drapes", "scraped", "scrape",
        "trapeze",

        // Contains "kill"
        "skill", "skills", "skilled", "skillful", "unskilled", "thrill",
        "thrilled", "thriller", "grill", "grilled", "grilling", "trill",
        "frill", "frilly", "chill", "chilled", "chilling", "chilly",
        "drill", "drilled", "drilling", "spill", "spilled", "spilling",
        "fill", "filled", "filling", "refill", "bill", "billed", "billing",
        "mill", "milled", "milling", "pill", "pills", "pillar", "will",
        "willing", "unwilling", "willingness", "goodwill", "freewill",
        "still", "instill", "distill", "distillery", "vanilla", "gorilla",
        "killer", "killers", // These might be ok in context like "killer app"

        // Contains "die/died"
        "studied", "studies", "remedied", "remedies", "odies", "bodies",
        "ladies", "diehard", "diesel",

        // Technology terms
        "execute", "executed", "execution", "executable",
        "master", "masters", "mastered", "mastering", "masterpiece",
        "slave", // In tech context (master/slave)
        "abort", "aborted", "aborting",
        "dump", "dumped", "dumping",
        "semaphore",
        "socket", "sockets",
        "daemon", "daemons",
        "spawn", "spawned", "spawning",
        "fork", "forked", "forking",
        "pipe", "piped", "piping", "pipeline",
        "crack", "cracked", "cracking", // Can be legit in tech
        "exploit", "exploited", "exploitation", // Security context
        "injection", // SQL injection
        "penetration", // Penetration testing
        "backdoor", // Security context
    )

    // Minimum length for flagging single "bad" words
    // Very short matches are more likely to be false positives
    private val minFlagLength = 3

    /**
     * Update custom word lists from parent configuration.
     * This should be called when word lists are synced from the backend.
     *
     * @param whitelist Words to ignore (won't be flagged)
     * @param blacklist Custom words to flag
     */
    fun updateCustomWordLists(
        whitelist: List<String>,
        blacklist: List<CustomBlacklistWord>
    ) {
        // Store whitelist as lowercase set for fast lookup
        // Include built-in false positive words
        customWhitelist = (whitelist.map { it.lowercase().trim() } + falsePositiveWords).toSet()
        customBlacklist = blacklist

        // Pre-compile blacklist patterns for efficiency
        compiledBlacklistPatterns = blacklist.map { word ->
            val escapedWord = Pattern.quote(word.word)
            val patternStr = if (word.wholeWordOnly) {
                "\\b$escapedWord\\b"
            } else {
                escapedWord
            }
            val flags = if (word.caseSensitive) 0 else Pattern.CASE_INSENSITIVE
            Pattern.compile(patternStr, flags) to word
        }
    }

    /**
     * Check if text contains whitelisted/false-positive words.
     * This prevents flagging innocent words like "class", "assessment", etc.
     */
    private fun isWhitelisted(text: String): Boolean {
        if (customWhitelist.isEmpty()) return false

        val lowerText = text.lowercase()

        // Check if any word in the text is a whitelisted word
        val words = lowerText.split(Regex("[\\s\\p{Punct}]+"))
        return words.any { word ->
            word in customWhitelist
        }
    }

    /**
     * Check if a specific detected word is actually part of an innocent word.
     * For example, "ass" in "class" or "assessment" should not be flagged.
     *
     * @param text The original text
     * @param badWord The potentially bad word that was detected
     * @return true if this is a false positive (innocent word contains the bad substring)
     */
    private fun isFalsePositive(text: String, badWord: String): Boolean {
        val lowerText = text.lowercase()
        val lowerBad = badWord.lowercase()

        // Find all words in the text that contain the bad word as a substring
        val words = lowerText.split(Regex("[\\s\\p{Punct}]+"))

        for (word in words) {
            if (word.contains(lowerBad) && word != lowerBad) {
                // The bad word is a substring of a larger word
                // Check if the larger word is in our false positive list
                if (word in falsePositiveWords || word in customWhitelist) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Extract individual words from text for analysis.
     */
    private fun extractWords(text: String): List<String> {
        return text.lowercase()
            .split(Regex("[\\s\\p{Punct}]+"))
            .filter { it.length >= minFlagLength }
    }

    /**
     * Check custom blacklist for matches.
     * Returns the matched word info if found.
     */
    private fun checkCustomBlacklist(text: String): CustomBlacklistWord? {
        for ((pattern, wordInfo) in compiledBlacklistPatterns) {
            if (pattern.matcher(text).find()) {
                return wordInfo
            }
        }
        return null
    }

    /**
     * Analyze text for inappropriate content using regex patterns.
     *
     * Uses a tiered approach to balance detection vs false positives:
     * 1. Original text - catches plain inappropriate content
     * 2. Normalized text - catches evasion (leetspeak, homoglyphs, etc.)
     * 3. Aggressive normalization - ONLY for high-severity categories with extra checks
     *
     * False positive prevention:
     * - Checks against whitelist of innocent words containing "bad" substrings
     * - Uses word boundary matching in regex patterns
     * - Requires context for certain detections
     *
     * @param text Text to analyze
     * @return TextAnalysisResult with detection details
     */
    fun analyze(text: String): TextAnalysisResult {
        if (text.isBlank()) {
            return TextAnalysisResult.safe()
        }

        // Two levels of normalization (aggressive only used selectively)
        val normalizedText = normalizeText(text)

        val detectedCategories = mutableListOf<String>()
        var maxConfidence = 0f
        var reason: String? = null

        // Helper to check patterns against original and normalized text
        // Does NOT use aggressive normalization by default (too many false positives)
        fun matchesPatternsConservative(patterns: List<Pattern>): Boolean {
            return matchesAnyPattern(text, patterns) ||
                    matchesAnyPattern(normalizedText, patterns)
        }

        // Helper for high-risk patterns - uses all three levels but with false positive check
        fun matchesPatternsWithFalsePositiveCheck(patterns: List<Pattern>): Boolean {
            // First try original and normalized
            if (matchesAnyPattern(text, patterns) || matchesAnyPattern(normalizedText, patterns)) {
                return true
            }

            // For aggressive normalization, be more careful
            val aggressiveText = aggressiveNormalize(text)
            if (matchesAnyPattern(aggressiveText, patterns)) {
                // Only flag if the aggressive-normalized text is significantly different
                // This catches real evasion but not normal text
                val originalWords = extractWords(text)
                val hasRealEvasion = originalWords.any { word ->
                    // Word contains suspicious characters (numbers, symbols mixed with letters)
                    word.any { it.isDigit() || it in "@$!#%^&*" } ||
                            // Word has unusual Unicode
                            word.any { it.code > 127 }
                }
                return hasRealEvasion
            }
            return false
        }

        // ===== CUSTOM BLACKLIST CHECK (HIGHEST PRIORITY) =====
        // Parent-defined blacklisted words are always flagged (no false positive check)
        val blacklistMatch = checkCustomBlacklist(text) ?: checkCustomBlacklist(normalizedText)

        if (blacklistMatch != null) {
            detectedCategories.add(blacklistMatch.category)
            maxConfidence = 0.98f
            reason = "Custom blacklisted word detected: ${blacklistMatch.word}"

            return TextAnalysisResult(
                isFlagged = true,
                confidence = maxConfidence,
                categories = detectedCategories,
                reason = reason
            )
        }

        // ===== FALSE POSITIVE CHECK =====
        // Check if text contains known innocent words that might trigger patterns
        val words = extractWords(text) + extractWords(normalizedText)
        val containsFalsePositiveWord = words.any { it in falsePositiveWords || it in customWhitelist }

        // ===== WHITELIST CHECK =====
        val hasWhitelistedContent = isWhitelisted(text) || isWhitelisted(normalizedText)

        // ==================== HIGH SEVERITY CATEGORIES ====================
        // These are never whitelisted and use more aggressive detection

        // 1. Self-harm (HIGHEST PRIORITY - uses aggressive detection)
        if (matchesPatternsWithFalsePositiveCheck(selfHarmPatterns) ||
            matchesAnyPattern(text, arabicSelfHarmPatterns)) {
            detectedCategories.add("self_harm")
            maxConfidence = maxOf(maxConfidence, 0.95f)
            reason = "Self-harm content detected"
        }

        // 2. Predator/Grooming patterns (uses aggressive detection)
        if (matchesPatternsWithFalsePositiveCheck(predatorPatterns) ||
            matchesAnyPattern(text, arabicPredatorPatterns)) {
            detectedCategories.add("predator_grooming")
            maxConfidence = maxOf(maxConfidence, 0.90f)
            reason = reason ?: "Potential grooming behavior detected"
        }

        // 3. Harmful behavior encouragement
        if (matchesPatternsConservative(harmfulBehaviorPatterns) ||
            matchesAnyPattern(text, arabicHarmfulBehaviorPatterns)) {
            detectedCategories.add("harmful_behavior")
            maxConfidence = maxOf(maxConfidence, 0.88f)
            reason = reason ?: "Harmful behavior encouragement detected"
        }

        // ==================== STANDARD CATEGORIES ====================
        // These can be overridden by whitelist and use conservative detection

        if (!hasWhitelistedContent && !containsFalsePositiveWord) {
            // 4. Profanity
            if (matchesPatternsConservative(profanityPatterns) ||
                matchesAnyPattern(text, arabicProfanityPatterns)) {
                detectedCategories.add("profanity")
                maxConfidence = maxOf(maxConfidence, 0.85f)
                reason = reason ?: "Profanity detected"
            }

            // 5. Bullying
            if (matchesPatternsConservative(bullyingPatterns) ||
                matchesAnyPattern(text, arabicBullyingPatterns)) {
                detectedCategories.add("bullying")
                maxConfidence = maxOf(maxConfidence, 0.88f)
                reason = reason ?: "Bullying content detected"
            }

            // 6. Violence - be more careful, many false positives
            if (matchesPatternsConservative(violencePatterns) ||
                matchesAnyPattern(text, arabicViolencePatterns)) {
                // Extra check: make sure it's not a common phrase
                val lowerText = text.lowercase()
                val isFalsePositive = lowerText.contains("killer app") ||
                        lowerText.contains("killer feature") ||
                        lowerText.contains("lady killer") ||
                        lowerText.contains("pain killer") ||
                        lowerText.contains("weed killer") ||
                        lowerText.contains("time killer") ||
                        lowerText.contains("killed it") ||
                        lowerText.contains("killing it")

                if (!isFalsePositive) {
                    detectedCategories.add("violence")
                    maxConfidence = maxOf(maxConfidence, 0.85f)
                    reason = reason ?: "Violent content detected"
                }
            }

            // 7. Sexual content
            if (matchesPatternsConservative(sexualPatterns) ||
                matchesAnyPattern(text, arabicSexualPatterns)) {
                detectedCategories.add("sexual")
                maxConfidence = maxOf(maxConfidence, 0.90f)
                reason = reason ?: "Sexual content detected"
            }

            // 8. Drugs - check for context
            if (matchesPatternsConservative(drugsPatterns) ||
                matchesAnyPattern(text, arabicDrugsPatterns)) {
                // Extra check for common false positives
                val lowerText = text.lowercase()
                val isFalsePositive = lowerText.contains("drug store") ||
                        lowerText.contains("drugstore") ||
                        lowerText.contains("prescription") ||
                        lowerText.contains("pharmacy") ||
                        lowerText.contains("medication") ||
                        lowerText.contains("medicine")

                if (!isFalsePositive) {
                    detectedCategories.add("drugs")
                    maxConfidence = maxOf(maxConfidence, 0.85f)
                    reason = reason ?: "Drug-related content detected"
                }
            }
        }

        return if (detectedCategories.isNotEmpty()) {
            TextAnalysisResult(
                isFlagged = true,
                confidence = maxConfidence,
                categories = detectedCategories,
                reason = reason
            )
        } else {
            TextAnalysisResult.safe()
        }
    }

    /**
     * Normalize text to defeat evasion techniques.
     *
     * Handles:
     * 1. Zero-width/invisible characters (f​u​c​k → fuck)
     * 2. Unicode homoglyphs (fuсk with Cyrillic → fuck)
     * 3. Full-width characters (ｆｕｃｋ → fuck)
     * 4. Combining diacritics (f̷u̷c̷k̷ → fuck)
     * 5. Leetspeak (f4ck, $h1t → fack, shit)
     * 6. Accented characters (fück → fuck)
     * 7. Excessive spacing/separators (f.u.c.k → fuck)
     */
    private fun normalizeText(text: String): String {
        val normalized = StringBuilder()

        for (char in text) {
            // Skip zero-width and invisible characters
            if (char in zeroWidthChars) continue

            // Convert full-width ASCII to regular ASCII (U+FF01-U+FF5E → U+0021-U+007E)
            val processedChar = if (char.code in 0xFF01..0xFF5E) {
                (char.code - fullWidthOffset).toChar()
            } else {
                char
            }

            // Convert to lowercase
            val lowerChar = processedChar.lowercaseChar()

            // Apply homoglyph mapping (Cyrillic/Greek → Latin)
            val afterHomoglyph = homoglyphMap[lowerChar] ?: lowerChar

            // Apply leetspeak mapping
            val finalChar = leetMap[afterHomoglyph] ?: afterHomoglyph

            normalized.append(finalChar)
        }

        // Remove combining diacritical marks (strikethrough, accents added via combining chars)
        val withoutDiacritics = combiningDiacriticsRegex.replace(normalized.toString(), "")

        // Collapse multiple spaces, dots, underscores, asterisks, dashes, and other separators
        // This catches: f.u.c.k, f_u_c_k, f*u*c*k, f-u-c-k, f u c k
        return withoutDiacritics
            .replace(Regex("[\\s._*\\-~`'\",:;!?|/\\\\]+"), " ")
            .trim()
    }

    /**
     * Additional aggressive normalization for stubborn evasion.
     * Removes ALL non-alphanumeric characters and collapses to pure letters.
     * Use this as a secondary check if normal normalization misses something.
     */
    private fun aggressiveNormalize(text: String): String {
        val normalized = normalizeText(text)
        // Keep only letters (removes all numbers, spaces, symbols)
        return normalized.filter { it.isLetter() }
    }

    /**
     * Check if text matches any of the given patterns.
     */
    private fun matchesAnyPattern(text: String, patterns: List<Pattern>): Boolean {
        for (pattern in patterns) {
            if (pattern.matcher(text).find()) {
                return true
            }
        }
        return false
    }
}
