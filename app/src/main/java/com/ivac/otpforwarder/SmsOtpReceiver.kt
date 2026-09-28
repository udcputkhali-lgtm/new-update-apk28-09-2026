package com.ivac.otpforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.regex.Pattern

class SmsOtpReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsOtpReceiver"
        private const val DEFAULT_FORWARD_URL = "https://ais-dev-r6fwghsme2uhic2o5q6fsu-466272014554.asia-southeast1.run.app/api/otp/push"
        private val httpClient = OkHttpClient()

        private val WORD_MAP = mapOf(
            "zero" to "0", "one" to "1", "two" to "2", "three" to "3",
            "four" to "4", "five" to "5", "six" to "6", "seven" to "7",
            "eight" to "8", "nine" to "9"
        )

        private val BANGLA_MAP = mapOf(
            '০' to '0', '১' to '1', '২' to '2', '৩' to '3', '৪' to '4',
            '৫' to '5', '৬' to '6', '৭' to '7', '৮' to '8', '৯' to '9'
        )

        /**
         * Robust OTP extraction supporting:
         * 1. English digits (4-8 digits)
         * 2. Bengali numerals (০-৯) converted to ASCII
         * 3. Spelled-out English words: "three-eight-two-one-nine-zero"
         * 4. Hyphenated / spaced digits: "3-8-2-1-9-0" or "3 8 2 1 9 0"
         */
        fun extractOtpCode(rawMessage: String): String? {
            if (rawMessage.isBlank()) return null

            // 1. Convert Bengali numbers (০-৯) to English digits
            val converted = StringBuilder()
            for (ch in rawMessage) {
                converted.append(BANGLA_MAP[ch] ?: ch)
            }
            val message = converted.toString()

            // 2. Check for spelled-out English digits
            val lower = message.toLowerCase()
            val wordRegex = Pattern.compile("\\b(zero|one|two|three|four|five|six|seven|eight|nine)(?:[-–—\\s,]+(zero|one|two|three|four|five|six|seven|eight|nine)){3,7}\\b", Pattern.CASE_INSENSITIVE)
            val wordMatcher = wordRegex.matcher(lower)
            if (wordMatcher.find()) {
                val matchedWords = wordMatcher.group(0) ?: ""
                val tokens = matchedWords.split(Regex("[-–—\\s,]+"))
                val digits = StringBuilder()
                for (t in tokens) {
                    WORD_MAP[t.trim()]?.let { digits.append(it) }
                }
                if (digits.length in 4..8) {
                    return digits.toString()
                }
            }

            // 3. Check for spaced or hyphenated digits
            val spacedRegex = Pattern.compile("(?<!\\d)(\\d[-–—\\s]\\d[-–—\\s]\\d[-–—\\s]\\d(?:[-–—\\s]\\d){0,4})(?!\\d)")
            val spacedMatcher = spacedRegex.matcher(message)
            if (spacedMatcher.find()) {
                val digitsOnly = spacedMatcher.group(1)?.replace(Regex("[^0-9]"), "") ?: ""
                if (digitsOnly.length in 4..8) {
                    return digitsOnly
                }
            }

            // 4. Standard 6-digit consecutive match (IVAC standard)
            val pattern6 = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)")
            val matcher6 = pattern6.matcher(message)
            if (matcher6.find()) {
                return matcher6.group(1)
            }

            // 5. Look for keyword nearby (OTP, code, pin, verification, password, আইভ্যাক, ওটিপি)
            val keywordRegex = Pattern.compile("(?i)(?:otp|code|pin|verification|password|আইভ্যাক|ওটিপি)[^0-9]*(\\d{4,8})")
            val keywordMatcher = keywordRegex.matcher(message)
            if (keywordMatcher.find()) {
                return keywordMatcher.group(1)
            }

            // 6. Generic 4-to-8 digit sequence fallback
            val patternAny = Pattern.compile("(?<!\\d)(\\d{4,8})(?!\\d)")
            val matcherAny = patternAny.matcher(message)
            if (matcherAny.find()) {
                return matcherAny.group(1)
            }

            return null
        }

        fun saveSmsToHistory(
            context: Context,
            smsText: String,
            sender: String,
            simSlot: Int,
            otpCode: String,
            timestamp: Long,
            status: String
        ) {
            try {
                val prefs = context.getSharedPreferences("ivac_otp_prefs", Context.MODE_PRIVATE)
                val existingHistoryJson = prefs.getString("sms_history_json", "[]") ?: "[]"
                val jsonArray = JSONArray(existingHistoryJson)

                val newItem = JSONObject().apply {
                    put("smsText", smsText)
                    put("sender", sender)
                    put("simSlot", simSlot)
                    put("otpCode", otpCode)
                    put("timestamp", timestamp)
                    put("status", status)
                }

                val newArray = JSONArray()
                newArray.put(newItem)

                // Keep up to 15 items
                val maxItems = 15
                for (i in 0 until minOf(jsonArray.length(), maxItems - 1)) {
                    newArray.put(jsonArray.getJSONObject(i))
                }

                prefs.edit().putString("sms_history_json", newArray.toString()).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Error saving SMS to history", e)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            val fullBody = StringBuilder()
            var sender = ""

            for (sms in messages) {
                fullBody.append(sms.messageBody)
                sender = sms.displayOriginatingAddress ?: ""
            }

            val smsText = fullBody.toString()
            Log.d(TAG, "Incoming SMS from: $sender -> $smsText")

            val subId = intent.extras?.getInt("subscription", -1) ?: -1
            val simSlotIndex = intent.extras?.getInt("slot", -1) ?: -1

            val simSlotNumber = if (simSlotIndex >= 0) {
                simSlotIndex + 1
            } else {
                val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                val subInfo = subManager?.activeSubscriptionInfoList?.find { it.subscriptionId == subId }
                (subInfo?.simSlotIndex ?: 0) + 1
            }

            // Get target phone number and server URL from preferences
            val prefs = context.getSharedPreferences("ivac_otp_prefs", Context.MODE_PRIVATE)
            val phoneSim1 = prefs.getString("sim1_number", "") ?: ""
            val phoneSim2 = prefs.getString("sim2_number", "") ?: ""
            val customServerUrl = prefs.getString("server_url", DEFAULT_FORWARD_URL) ?: DEFAULT_FORWARD_URL

            val targetPhoneNumber = if (simSlotNumber == 2 && phoneSim2.isNotBlank()) {
                phoneSim2
            } else if (simSlotNumber == 1 && phoneSim1.isNotBlank()) {
                phoneSim1
            } else {
                phoneSim1.ifBlank { phoneSim2 }
            }

            val finalTarget = targetPhoneNumber.ifBlank { "auto" }

            // Extract 4-to-8 digit OTP code
            val otpCode = extractOtpCode(smsText)
            val timeNow = System.currentTimeMillis()
            val initialStatus = if (!otpCode.isNullOrBlank()) "SENDING" else "NO_OTP"

            // Save to SharedPreferences for on-screen inspection
            prefs.edit()
                .putString("last_sms_text", smsText)
                .putString("last_sms_sender", sender)
                .putInt("last_sms_sim_slot", simSlotNumber)
                .putString("last_sms_otp", otpCode ?: "")
                .putLong("last_sms_timestamp", timeNow)
                .putString("last_sms_forward_status", initialStatus)
                .putString("last_sms_status_detail", "সার্ভারে পাঠানো হচ্ছে...")
                .apply()

            saveSmsToHistory(context, smsText, sender, simSlotNumber, otpCode ?: "", timeNow, initialStatus)

            // Broadcast to MainActivity to refresh UI in real time
            val notifyIntent = Intent("com.ivac.otpforwarder.ACTION_SMS_CAPTURED").apply {
                setPackage(context.packageName)
                putExtra("smsText", smsText)
                putExtra("sender", sender)
                putExtra("simSlot", simSlotNumber)
                putExtra("otpCode", otpCode ?: "")
                putExtra("timestamp", timeNow)
                putExtra("status", initialStatus)
            }
            context.sendBroadcast(notifyIntent)

            if (!otpCode.isNullOrBlank()) {
                Log.d(TAG, "Dispatched OTP [$otpCode] from SIM $simSlotNumber to $finalTarget via $customServerUrl")
                forwardOtpToServer(
                    context = context,
                    serverUrl = customServerUrl,
                    phoneNumber = finalTarget,
                    otpCode = otpCode,
                    simSlot = simSlotNumber,
                    simName = if (simSlotNumber == 2) "SIM 2" else "SIM 1",
                    rawSender = sender,
                    rawText = smsText
                )
            } else {
                Log.w(TAG, "Could not extract OTP from SMS body: $smsText")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming SMS", e)
        }
    }

    private fun forwardOtpToServer(
        context: Context,
        serverUrl: String,
        phoneNumber: String,
        otpCode: String,
        simSlot: Int,
        simName: String,
        rawSender: String,
        rawText: String
    ) {
        val jsonPayload = JSONObject().apply {
            put("phoneNumber", phoneNumber)
            put("otpCode", otpCode)
            put("simSlot", simSlot)
            put("simName", simName)
            put("rawSender", rawSender)
            put("rawText", rawText)
        }

        val requestBody = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(serverUrl)
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to push OTP to server: $serverUrl", e)
                updateStatus(context, "FAILED", e.localizedMessage ?: "কানেকশন ফেইল্ড")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        Log.i(TAG, "OTP [$otpCode] successfully routed to server: ${response.code}")
                        updateStatus(context, "SUCCESS", "HTTP ${response.code} (সফল)")
                    } else {
                        Log.w(TAG, "Server returned error: ${response.code}")
                        updateStatus(context, "FAILED", "HTTP ${response.code} ত্রুটি")
                    }
                }
            }
        })
    }

    private fun updateStatus(context: Context, status: String, detail: String) {
        val prefs = context.getSharedPreferences("ivac_otp_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("last_sms_forward_status", status)
            .putString("last_sms_status_detail", detail)
            .apply()

        val intent = Intent("com.ivac.otpforwarder.ACTION_SMS_STATUS_UPDATE").apply {
            setPackage(context.packageName)
            putExtra("status", status)
            putExtra("detail", detail)
        }
        context.sendBroadcast(intent)
    }
}
