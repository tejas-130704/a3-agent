package com.agent.ai.data.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** Shared contact resolution for dialer, WhatsApp, and Telegram tools. */
object ContactLookup {

    data class ContactMatch(val displayName: String, val phone: String, val score: Int = 0)

    private const val MIN_LIST_SCORE = 25
    private const val MIN_CONFIRM_SCORE = 50

    /** Strips emoji and punctuation so "Aaai 💕" matches spoken "Aai". */
    fun normalizeForMatch(text: String): String {
        return text
            .replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+"), "")
            .replace(Regex("[\\p{So}\\p{Sk}]"), "")
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
    }

    /** First meaningful token — e.g. "aaai" from "Aaai 💕". */
    fun primaryToken(displayName: String): String {
        return normalizeForMatch(displayName).split(Regex("\\s+")).firstOrNull().orEmpty()
    }

    fun topFrequentContacts(context: Context, limit: Int = 20): List<ContactMatch> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        val phoneByContactId = mutableMapOf<Long, String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { phoneCursor ->
            val idIdx = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val numIdx = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (phoneCursor.moveToNext()) {
                val id = phoneCursor.getLong(idIdx)
                val number = phoneCursor.getString(numIdx)?.replace(Regex("[^0-9+]"), "") ?: continue
                if (number.isNotBlank() && !phoneByContactId.containsKey(id)) {
                    phoneByContactId[id] = number
                }
            }
        }

        val results = mutableListOf<ContactMatch>()
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.TIMES_CONTACTED
            ),
            "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1",
            null,
            "${ContactsContract.Contacts.TIMES_CONTACTED} DESC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
            val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            while (cursor.moveToNext() && results.size < limit) {
                val id = cursor.getLong(idIdx)
                val name = cursor.getString(nameIdx)?.trim().orEmpty()
                val phone = phoneByContactId[id] ?: continue
                if (name.isBlank()) continue
                if (results.any { it.displayName.equals(name, ignoreCase = true) }) continue
                results.add(ContactMatch(name, phone))
            }
        }

        if (results.size < limit) {
            loadAllContacts(context).forEach { contact ->
                if (results.size >= limit) return@forEach
                if (results.none { it.displayName.equals(contact.displayName, ignoreCase = true) }) {
                    results.add(contact)
                }
            }
        }

        return results.take(limit)
    }

    fun buildFrequentContactsPrompt(context: Context, limit: Int = 20): String {
        val contacts = topFrequentContacts(context, limit)
        if (contacts.isEmpty()) return ""
        return buildString {
            appendLine("TOP FREQUENT CONTACTS:")
            contacts.forEachIndexed { index, contact ->
                appendLine("${index + 1}. ${contact.displayName}")
            }
        }
    }

    fun findTopMatches(context: Context, queryName: String, limit: Int = 4): List<ContactMatch> {
        val query = queryName.trim()
        if (query.isEmpty()) return emptyList()

        val pool = loadAllContacts(context)
        if (pool.isEmpty()) return emptyList()

        val frequentNames = topFrequentContacts(context, 20)
            .map { normalizeForMatch(it.displayName) }
            .toSet()

        val scored = pool
            .map { contact ->
                var score = matchScore(contact.displayName, query)
                if (normalizeForMatch(contact.displayName) in frequentNames) score += 8
                contact.copy(score = score)
            }
            .filter { it.score >= MIN_LIST_SCORE }
            .sortedByDescending { it.score }

        val deduped = mutableListOf<ContactMatch>()
        val seenPhones = mutableSetOf<String>()
        for (match in scored) {
            if (seenPhones.add(match.phone)) deduped.add(match)
            if (deduped.size >= limit) break
        }
        return deduped
    }

    fun formatConfirmationPrompt(query: String, matches: List<ContactMatch>): String {
        if (matches.isEmpty()) {
            return "I couldn't find any contacts matching \"$query\"."
        }
        return buildString {
            append("I found ${matches.size} contact${if (matches.size == 1) "" else "s"} matching \"$query\". Which one should I call?\n")
            matches.forEachIndexed { index, match ->
                append("${index + 1}. ${match.displayName}\n")
            }
            append("Say the number or the exact name to confirm.")
        }
    }

    fun resolveFromCandidates(
        candidates: List<ContactMatch>,
        contactName: String?,
        choiceIndex: Int?
    ): ContactMatch? {
        if (candidates.isEmpty()) return null

        val parsedIndex = choiceIndex ?: parseChoiceIndex(contactName)
        if (parsedIndex != null && parsedIndex in 1..candidates.size) {
            return candidates[parsedIndex - 1]
        }

        val name = contactName?.trim().orEmpty()
        if (name.isEmpty()) return null

        // When picking from a fixed candidate list, use a lower bar than open-ended search
        var best: ContactMatch? = null
        var bestScore = Int.MIN_VALUE
        for (candidate in candidates) {
            val score = matchScore(candidate.displayName, name)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return if (bestScore >= 35) best else null
    }

    /** Parse spoken or typed choice: "1", "one", "number 2", etc. */
    fun parseChoiceIndex(text: String?): Int? {
        val raw = text?.trim()?.lowercase().orEmpty()
        if (raw.isEmpty()) return null

        raw.toIntOrNull()?.takeIf { it in 1..4 }?.let { return it }

        val word = raw
            .removePrefix("number")
            .removePrefix("option")
            .trim()
            .split(Regex("\\s+"))
            .firstOrNull()
            .orEmpty()

        return when (word) {
            "1", "one", "first" -> 1
            "2", "two", "second" -> 2
            "3", "three", "third" -> 3
            "4", "four", "fourth" -> 4
            else -> word.toIntOrNull()?.takeIf { it in 1..4 }
        }
    }

    fun findByName(context: Context, queryName: String, candidatePool: List<ContactMatch>? = null): ContactMatch? {
        findTopMatches(context, queryName, limit = 1).firstOrNull()?.let { return it }
        val pool = candidatePool ?: loadAllContacts(context)
        return pool.maxByOrNull { matchScore(it.displayName, queryName) }
            ?.takeIf { matchScore(it.displayName, queryName) >= MIN_CONFIRM_SCORE }
    }

    fun resolveSpokenName(context: Context, spokenOrLlmName: String): ContactMatch? {
        return findByName(context, spokenOrLlmName)
    }

    private fun loadAllContacts(context: Context): List<ContactMatch> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val results = mutableListOf<ContactMatch>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx)?.trim().orEmpty()
                val number = cursor.getString(numIdx)?.replace(Regex("[^0-9+]"), "") ?: continue
                if (name.isBlank()) continue
                results.add(ContactMatch(name, number))
            }
        }
        return results
    }

    fun matchScore(displayName: String, query: String): Int {
        val nameNorm = normalizeForMatch(displayName)
        val queryNorm = normalizeForMatch(query)
        if (nameNorm.isEmpty() || queryNorm.isEmpty()) return Int.MIN_VALUE

        val primary = primaryToken(displayName)
        return listOf(
            scorePair(nameNorm, queryNorm),
            scorePair(primary, queryNorm)
        ).maxOrNull() ?: Int.MIN_VALUE
    }

    private fun scorePair(name: String, query: String): Int {
        if (name.isEmpty() || query.isEmpty()) return Int.MIN_VALUE

        val base = when {
            name == query -> 100
            name.startsWith(query) -> 90
            query.startsWith(name) -> 85
            name.contains(query) -> 75
            query.contains(name) -> 70
            else -> {
                val nameTokens = name.split(Regex("\\s+"))
                val queryTokens = query.split(Regex("\\s+"))
                val overlap = queryTokens.count { qt ->
                    nameTokens.any { nt ->
                        nt.startsWith(qt) || qt.startsWith(nt) || similarity(nt, qt) >= 0.72
                    }
                }
                if (overlap > 0) 55 + overlap * 15 else 0
            }
        }

        return base + (similarity(name, query) * 50).toInt()
    }

    fun similarity(a: String, b: String): Double {
        val left = normalizeForMatch(a)
        val right = normalizeForMatch(b)
        val maxLen = maxOf(left.length, right.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(left, right).toDouble() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                curr[j + 1] = minOf(curr[j] + 1, prev[j + 1] + 1, prev[j] + cost)
            }
            for (j in prev.indices) prev[j] = curr[j]
        }
        return prev[b.length]
    }

    fun digitsOnly(phone: String): String = phone.replace(Regex("[^0-9]"), "")
}
