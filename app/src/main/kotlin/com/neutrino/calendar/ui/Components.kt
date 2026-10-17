package com.neutrino.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neutrino.calendar.R
import com.neutrino.calendar.core.model.UserCalendar
import com.neutrino.shared.android.ui.NeutrinoBrand
import com.neutrino.shared.android.ui.TrustRow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Calendar on the shared sign-in and lock screens: `NeutrinoBrand.calendar` from the iOS package,
 * purple into pink. No "end-to-end encrypted" claim: events are stored readable on the server, as
 * Google and Outlook sync needs; attachments are Drive files and are encrypted, so that row stays.
 */
val CalendarBrand = NeutrinoBrand(
    name = "Neutrino Calendar",
    tagline = "Your schedule, across every Neutrino device",
    primary = Color(0xFFAF52DE),
    secondary = Color(0xFFFF2D55),
    primaryDark = Color(0xFFD69CF0),
    secondaryDark = Color(0xFFFF6B88),
    icon = R.drawable.ic_launcher_art,
    trustRows = listOf(
        TrustRow(Icons.Filled.Sync, "Synced with your Neutrino account", Color(0xFF007AFF)),
        TrustRow(Icons.Filled.CalendarMonth, "Google, Outlook and iCloud in one place", Color(0xFFAF52DE)),
        TrustRow(Icons.Filled.AttachFile, "Attachments encrypted in Drive", Color(0xFF34C759)),
    ),
    lockedSubtitle = "Your calendar is on this phone.",
    registerSubtitle = "One account for every Neutrino app. Attachments are encrypted on this phone before they're uploaded.",
    encryptionBlurb = "Event attachments and saved places are encrypted with a key only you hold. It stays on this phone and is never sent to the server.",
)

/** How events and tasks look: an event in its calendar's colour, a task in an amber no calendar is offered. */
object EventStyle {
    val taskColor = Color(0xFFF59E0B)
    val warning = Color(0xFFE08A00)
}

/** A calendar's `#rrggbb`, or null for anything else, so a bad value falls back instead of drawing black. */
fun hexColor(hex: String?): Color? {
    val text = hex?.trim()?.removePrefix("#") ?: return null
    if (text.length != 6) return null
    return text.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}

/** The Compact layout setting, for every row and section. */
val LocalCompact = staticCompositionLocalOf { false }

// ── Pages and form sections ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = actions,
    )
}

/** A grouped section, as an iOS inset list draws one: a header, rounded rows, a footer. */
@Composable
fun FormSection(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val compact = LocalCompact.current
    Column(Modifier.fillMaxWidth().padding(horizontal = if (compact) 8.dp else 16.dp, vertical = if (compact) 4.dp else 8.dp)) {
        if (header != null) {
            Text(header.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest), content = content)
        if (footer != null) {
            Text(footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp))
        }
    }
}

/** One row of a section. */
@Composable
fun FormRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    divider: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val compact = LocalCompact.current
    Column {
        Row(
            modifier.fillMaxWidth().heightIn(min = if (compact) 40.dp else 50.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (divider) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
fun ValueRow(label: String, value: String, icon: ImageVector? = null, onClick: (() -> Unit)? = null, valueColor: Color? = null) {
    FormRow(onClick = onClick) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
        }
        Text(label, Modifier.weight(1f))
        Text(value, color = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    FormRow(onClick = { if (enabled) onChange(!checked) }) {
        Text(label, Modifier.weight(1f), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
fun ActionRow(label: String, icon: ImageVector? = null, color: Color? = null, enabled: Boolean = true, onClick: () -> Unit) {
    FormRow(onClick = if (enabled) onClick else null) {
        val tint = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant else color ?: MaterialTheme.colorScheme.primary
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = tint)
            Spacer(Modifier.width(12.dp))
        }
        Text(label, color = tint)
    }
}

/** A choice from a short list, shown as a menu from its row. */
@Composable
fun <T> ChoiceRow(label: String, value: T, choices: List<T>, describe: (T) -> String, onChoose: (T) -> Unit, leading: (@Composable (T) -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        ValueRow(label, describe(value), onClick = { open = true })
        DropdownMenu(open, { open = false }) {
            choices.forEach { c ->
                DropdownMenuItem(
                    text = { Text(describe(c)) },
                    leadingIcon = leading?.let { { it(c) } },
                    onClick = {
                        open = false
                        onChoose(c)
                    },
                )
            }
        }
    }
}

@Composable
fun ErrorText(message: String?, modifier: Modifier = Modifier) {
    message ?: return
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = EventStyle.warning)
        Spacer(Modifier.width(6.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = EventStyle.warning)
    }
}

@Composable
fun ColorDot(color: Color, size: Int = 10) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

/** A small rounded label: "Google", "Read-only". */
@Composable
fun Badge(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ── Date and time ───────────────────────────────────────────────────────────────────────────────

val mediumDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
val shortTime: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** A date picker over a [LocalDate]; Material's picker counts days in UTC milliseconds. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayPickerDialog(initial: LocalDate, onDismiss: () -> Unit, minimum: LocalDate? = null, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : androidx.compose.material3.SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                minimum == null || !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(minimum)
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = rememberTimePickerState(initial.hour, initial.minute, android.text.format.DateFormat.is24HourFormat(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onPick(LocalTime.of(state.hour, state.minute))
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state) },
    )
}

/**
 * A date and, unless `dateOnly`, a time, as two tappable chips — the platform's own pickers behind
 * each. Times are entered and shown in `zone`, the event's zone.
 */
@Composable
fun DateTimeRow(label: String, value: Instant, zone: ZoneId, dateOnly: Boolean, minimum: LocalDate? = null, onChange: (Instant) -> Unit) {
    var pickingDay by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    val local = value.atZone(zone)
    FormRow {
        Text(label, Modifier.weight(1f))
        Chip(mediumDate.format(local.toLocalDate())) { pickingDay = true }
        if (!dateOnly) {
            Spacer(Modifier.width(8.dp))
            Chip(shortTime.format(local.toLocalTime())) { pickingTime = true }
        }
    }
    if (pickingDay) DayPickerDialog(local.toLocalDate(), { pickingDay = false }, minimum) { day ->
        onChange(java.time.ZonedDateTime.ofLocal(day.atTime(local.toLocalTime()), zone, null).toInstant())
    }
    if (pickingTime) TimePickerDialog(local.toLocalTime(), { pickingTime = false }) { time ->
        onChange(java.time.ZonedDateTime.ofLocal(local.toLocalDate().atTime(time), zone, null).toInstant())
    }
}

@Composable
fun Chip(text: String, onClick: () -> Unit) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** The palette of a calendar's colours, the web's eight. */
@Composable
fun ColorPalette(selection: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        UserCalendar.PALETTE.forEach { hex ->
            val selected = hex.equals(selection, ignoreCase = true)
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(hexColor(hex) ?: Color.Gray).clickable { onSelect(hex) },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Filled.Check, UserCalendar.PALETTE_NAMES[hex], tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** A tab's list body padding, so the last row clears the banners. */
val ListPadding = PaddingValues(bottom = 24.dp)

@Composable
fun CompactProvider(compact: Boolean, content: @Composable () -> Unit) = CompositionLocalProvider(LocalCompact provides compact, content = content)
