package com.neutrino.calendar.core.model

/**
 * Why a save stopped to ask before going ahead: the thing was changed, or deleted, on another device
 * or on the web after this device opened it.
 *
 * An edit sends only the fields it changed, so a change made elsewhere to a *different* field is
 * never overwritten and is not a conflict. The save stops only when both sides changed the same
 * field to different values.
 */
sealed class EditConflict(message: String) : Exception(message) {
    /** Both changed these fields, named for people ("title", "time"). */
    class ChangedElsewhere(val fields: List<String>) :
        EditConflict("Someone changed the ${listPhrase(fields)} on another device since you opened this.")

    class DeletedElsewhere : EditConflict("This was deleted on another device.")

    companion object {
        /**
         * The fields that `mine` and `theirs` both change, to different values. Both are update
         * requests measured from the same starting point, and an absent field means "unchanged".
         */
        fun clashes(mine: JsonBody, theirs: JsonBody): List<String> {
            val m = mine.toJson()
            val t = theirs.toJson()
            val labels = mutableListOf<String>()
            for (key in m.keys.sorted()) {
                val their = t[key] ?: continue
                if (their == m[key]) continue
                val label = label(key)
                if (label !in labels) labels += label
            }
            return labels
        }

        /** The wire names of the three update requests, as the forms label them. */
        private fun label(key: String): String = when (key) {
            "startTime", "endTime", "allDay" -> "time"
            "description", "notes" -> "notes"
            "recurrenceRule" -> "repeat"
            "attendees" -> "guests"
            "timezone" -> "time zone"
            "dueTime", "dueDate", "dueHasTime" -> "due date"
            "calendarId" -> "calendar"
            "geoPlaceId", "geoLat", "geoLng", "geoRadiusM" -> "place"
            else -> key
        }
    }
}
