package ai.unicto.unibot.ui.chat

// [v12-B] Chat templates / prompt starters: a grid of built-in starter cards
// grouped by category (writing, coding, planning, fun). Tapping a card opens
// a new chat with the template prefilled via [ChatStarterPrefill]; the user
// edits freely before sending. No network, no persistence — templates are
// compile-time constants.

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.R
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import ai.unicto.unibot.ui.theme.staggeredEntrance
import ai.unicto.unibot.ui.util.rememberHaptic
import java.util.UUID

/** Template category. */
enum class ChatTemplateCategory {
    WRITING, CODING, PLANNING, FUN;

    val icon: ImageVector
        get() = when (this) {
            WRITING -> Icons.Outlined.Description
            CODING -> Icons.Filled.Code
            PLANNING -> Icons.Outlined.CalendarMonth
            FUN -> Icons.Outlined.AutoAwesome
        }
}

/** One starter card. [prompt] is prefilled into the composer verbatim. */
data class ChatTemplate(
    val id: String,
    val category: ChatTemplateCategory,
    val title: String,
    val blurb: String,
    val prompt: String,
)

/** Built-in templates. Bracketed slots invite the user to fill in specifics. */
val ChatTemplates: List<ChatTemplate> = listOf(
    // ── Writing ──
    ChatTemplate(
        id = "writing-blog",
        category = ChatTemplateCategory.WRITING,
        title = "Blog post draft",
        blurb = "Hook intro, 3 sections, closing takeaway",
        prompt = "Write a draft blog post about [topic]. Tone: conversational. " +
            "About 600 words, with a hook intro, 3 sections with subheadings, " +
            "and a closing takeaway.",
    ),
    ChatTemplate(
        id = "writing-email",
        category = ChatTemplateCategory.WRITING,
        title = "Email polisher",
        blurb = "Clear, polite, concise — plus a subject line",
        prompt = "Rewrite the email below to be clear, polite, and concise. " +
            "Keep my meaning, fix grammar, and suggest a subject line:\n\n[paste email]",
    ),
    ChatTemplate(
        id = "writing-story",
        category = ChatTemplateCategory.WRITING,
        title = "Story starter",
        blurb = "An opening scene with a hook ending",
        prompt = "Write the opening scene of a short story. Genre: [pick one]. " +
            "Introduce one vivid character and end on a hook.",
    ),
    // ── Coding ──
    ChatTemplate(
        id = "coding-explain",
        category = ChatTemplateCategory.CODING,
        title = "Explain this code",
        blurb = "What it does, plus bugs and edge cases",
        prompt = "Explain what this code does, line by line where it matters, " +
            "and flag any bugs or edge cases:\n\n[paste code]",
    ),
    ChatTemplate(
        id = "coding-debug",
        category = ChatTemplateCategory.CODING,
        title = "Debug helper",
        blurb = "Error + code in, diagnosis out",
        prompt = "Help me debug this. Here is the error:\n\n[paste error]\n\n" +
            "And the relevant code:\n\n[paste code]\n\n" +
            "Ask me for anything else you need.",
    ),
    ChatTemplate(
        id = "coding-review",
        category = ChatTemplateCategory.CODING,
        title = "Code review",
        blurb = "Senior-engineer review: correctness to security",
        prompt = "Review this code as a senior engineer: correctness, " +
            "readability, performance, and security. Be specific:\n\n[paste code]",
    ),
    // ── Planning ──
    ChatTemplate(
        id = "planning-day",
        category = ChatTemplateCategory.PLANNING,
        title = "Day planner",
        blurb = "Prioritized schedule with breaks",
        prompt = "Plan my day. It is currently [time]. My tasks: [list]. " +
            "Prioritize by urgency, batch similar work, and add breaks.",
    ),
    ChatTemplate(
        id = "planning-trip",
        category = ChatTemplateCategory.PLANNING,
        title = "Trip itinerary",
        blurb = "Day-by-day plan: sights, food, downtime",
        prompt = "Plan a [N]-day trip to [place]. Budget: [budget]. Mix must-see " +
            "sights, food spots, and downtime. Give a day-by-day schedule.",
    ),
    ChatTemplate(
        id = "planning-goal",
        category = ChatTemplateCategory.PLANNING,
        title = "Goal breakdown",
        blurb = "Big goal → small actionable steps",
        prompt = "Break this goal into a step-by-step plan with milestones: " +
            "[goal]. Keep steps small and actionable.",
    ),
    // ── Fun ──
    ChatTemplate(
        id = "fun-trivia",
        category = ChatTemplateCategory.FUN,
        title = "Trivia quiz",
        blurb = "5 questions, one at a time, score tracked",
        prompt = "Quiz me on [topic]: 5 multiple-choice questions, one at a " +
            "time. Track my score and explain each answer.",
    ),
    ChatTemplate(
        id = "fun-wyr",
        category = ChatTemplateCategory.FUN,
        title = "Would you rather",
        blurb = "5 tricky dilemmas, reactive hosting",
        prompt = "Give me 5 tricky \"would you rather\" questions, one at a " +
            "time. React to my picks.",
    ),
    ChatTemplate(
        id = "fun-roast",
        category = ChatTemplateCategory.FUN,
        title = "Roast my idea",
        blurb = "Funny but honest, then one real fix",
        prompt = "Roast this idea — funny but honest — then tell me the one " +
            "thing that could actually make it work: [idea]",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTemplatesScreen(
    onBack: () -> Unit,
    onTemplateChosen: (draftSessionId: String) -> Unit,
) {
    val haptics = rememberHaptic()
    var selectedCategory by remember { mutableStateOf<ChatTemplateCategory?>(null) }
    val visible = remember(selectedCategory) {
        if (selectedCategory == null) ChatTemplates
        else ChatTemplates.filter { it.category == selectedCategory }
    }

    Scaffold(
        topBar = {
            MuseTopAppBar(
                title = { Text(stringResource(R.string.v12_chat_templates_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Text(
                text = stringResource(R.string.v12_chat_templates_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            // Category filter chips.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { haptics.tap(); selectedCategory = null },
                    label = { Text(stringResource(R.string.v12_chat_templates_cat_all)) },
                )
                ChatTemplateCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { haptics.tap(); selectedCategory = cat },
                        label = { Text(categoryLabel(cat)) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(visible, key = { _, t -> t.id }) { index, template ->
                    TemplateCard(
                        template = template,
                        modifier = Modifier.staggeredEntrance(index),
                        onClick = {
                            haptics.tap()
                            // Stage the starter text, then open a fresh chat —
                            // ChatViewModel consumes the staged text in init.
                            val draftId = "__new__${UUID.randomUUID()}"
                            ChatStarterPrefill.stage(draftId, template.prompt)
                            onTemplateChosen(draftId)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun categoryLabel(cat: ChatTemplateCategory): String = stringResource(
    when (cat) {
        ChatTemplateCategory.WRITING -> R.string.v12_chat_templates_cat_writing
        ChatTemplateCategory.CODING -> R.string.v12_chat_templates_cat_coding
        ChatTemplateCategory.PLANNING -> R.string.v12_chat_templates_cat_planning
        ChatTemplateCategory.FUN -> R.string.v12_chat_templates_cat_fun
    },
)

@Composable
private fun TemplateCard(
    template: ChatTemplate,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Icon(
            imageVector = template.category.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = template.title,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = template.blurb,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(36.dp),
        )
    }
}
