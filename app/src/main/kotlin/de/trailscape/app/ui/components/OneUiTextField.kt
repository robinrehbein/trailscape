package de.trailscape.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * # Das One-UI-Eingabefeld: Beschriftung **ueber** dem Feld
 *
 * Samsung setzt Formularfelder als ruhige, gefuellte Flaechen mit der
 * Beschriftung als kleine Zeile darueber. Material 3 macht das Gegenteil: Es
 * legt die Beschriftung *in* das Feld und laesst sie beim Tippen in den Rahmen
 * wandern (`OutlinedTextField` mit `label`).
 *
 * Der Unterschied ist nicht nur Geschmack — die schwebende Beschriftung ist in
 * dieser App sichtbar zerbrochen:
 *
 *  * In einem halbbreiten Feld bricht eine laengere Beschriftung um
 *    („Rad + Gepäck (kg)"), und das Feld wird zweizeilig hoch.
 *  * Beim Auswahlfeld „Geschlecht" tat der **Wert** dasselbe („keine
 *    Angabe" auf zwei Zeilen), weil ihm `singleLine` fehlte — die
 *    Beschriftung stand dann quer im Rahmen.
 *
 * Eine Beschriftung ueber dem Feld kann beides nicht: Sie ist 12 sp klein
 * (`labelMedium`), hat die volle Feldbreite und liegt nie im Weg. Das Feld
 * selbst bleibt strikt einzeilig.
 *
 * ## Warum gefuellt statt umrandet
 * One-UI-Felder sind eingelassene Flaechen ohne Rahmen. `surfaceContainer` ist
 * dafuer der richtige Slot: die getoente Stufe, die *auf* einer Karte liegt
 * (siehe `theme/Color.kt`). Die Unterstreichung von Material schalten wir ab —
 * die Flaeche allein zeigt schon, dass man hier tippen kann.
 */
@Composable
fun OneUiTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    trailingIcon: @Composable (() -> Unit)? = null,
    fieldModifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        OneUiFieldLabel(label)
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            readOnly = readOnly,
            enabled = enabled,
            trailingIcon = trailingIcon,
            singleLine = true,
            shape = MaterialTheme.shapes.extraSmall,
            colors = oneUiTextFieldColors(),
            modifier = fieldModifier.fillMaxWidth(),
        )
    }
}

/**
 * # Das Suchfeld — eine eigene Gattung, kein Formularfeld
 *
 * Samsung fuehrt „Search" als **eigene Komponente**, nicht als Sonderfall
 * eines Eingabefelds, und das aus gutem Grund: Ein Suchfeld hat keine
 * Beschriftung darueber (der Platzhalter sagt bereits alles), es traegt die
 * Lupe links im Feld, und es ist rund — eine volle Pille, wie die Suchzeile
 * in den Samsung-Einstellungen.
 *
 * Deshalb nicht [OneUiTextField] mit leerer Beschriftung: Die Regel „Label
 * ueber dem Feld" gilt fuer Formulare und soll dort gelten. Ein Suchfeld mit
 * dem Woertchen „Suche" darueber waere eine Zeile Text, die nichts sagt, was
 * die Lupe nicht schon zeigt.
 *
 * ## Der Anlass
 * Im Erkunden-Blatt der Karte stand hier bis dahin eine **Attrappe**: eine
 * `Row`, die aussah wie ein Feld, aber ein Knopf war und ein zweites,
 * modales Blatt mit dem echten Feld oeffnete. Zwei Blaetter, zwei Griffe,
 * zwei Felder fuer eine Aufgabe — und die Karte, um die es bei einer
 * Ortssuche geht, verschwand hinter dem Scrim. Dieses Feld ist das Original,
 * das die Attrappe abloest.
 *
 * @param onFocusChange Meldet, ob das Feld gerade den Fokus hat. Der Aufrufer
 *   entscheidet daraufhin, was er zeigt — im Erkunden-Blatt etwa den
 *   Suchverlauf statt der Tourenliste.
 * @param busy Zeigt statt des Loeschkreuzes einen Ring. Beides zugleich waere
 *   ein Ziel, das unter dem Finger die Bedeutung wechselt.
 */
@Composable
fun OneUiSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    onFocusChange: (Boolean) -> Unit = {},
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                // Die Lupe ist Dekor: Was das Feld tut, steht als Platzhalter
                // daneben und wird ohnehin vorgelesen.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            when {
                busy -> CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )

                value.isNotEmpty() -> IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = "Suche leeren")
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // Volle Pille statt der 18 dp der Formularfelder — so sieht Suche in
        // One UI aus, von den Einstellungen bis zur App-Uebersicht.
        shape = MaterialTheme.shapes.small,
        colors = oneUiTextFieldColors(),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { onFocusChange(it.isFocused) },
    )
}

/**
 * Ein Auswahlfeld im selben Gewand: dieselbe Flaeche, dieselbe Beschriftung
 * darueber, nur mit Pfeil und aufklappender Liste statt Tastatur.
 *
 * [options] ist bewusst eine Liste von Paaren und keine Map: Die Reihenfolge im
 * Menue ist Teil der Gestaltung, und eine `Map` verspricht sie nicht.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> OneUiDropdownField(
    label: String,
    value: T,
    options: List<Pair<T, String>>,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == value }?.second.orEmpty()

    Column(modifier = modifier) {
        OneUiFieldLabel(label)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            TextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                // Ohne `singleLine` bricht ein zweiwortiger Wert („keine
                // Angabe") im halbbreiten Feld um — genau der Fehler, den
                // diese Datei abstellt.
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                shape = MaterialTheme.shapes.extraSmall,
                colors = oneUiTextFieldColors(),
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { (optionValue, optionLabel) ->
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = {
                            onChange(optionValue)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/** Die kleine Zeile ueber dem Feld — einheitlich fuer Text- und Auswahlfeld. */
@Composable
private fun OneUiFieldLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

/**
 * Gefuellte Flaeche ohne Unterstreichung — in allen Zustaenden. Der Fokus zeigt
 * sich am Cursor und an der Tastatur, nicht an einem Strich unter dem Feld.
 */
@Composable
private fun oneUiTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
)
