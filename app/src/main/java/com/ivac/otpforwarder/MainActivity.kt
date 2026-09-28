package com.ivac.otpforwarder

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var etSim1Phone: EditText
    private lateinit var etSim2Phone: EditText
    private lateinit var tvSim1Status: TextView
    private lateinit var tvSim2Status: TextView
    private lateinit var tvServiceStatus: TextView
    private lateinit var btnSave: Button
    private lateinit var btnRequestBattery: Button
    private lateinit var llActivityContainer: LinearLayout
    private lateinit var tvNoActivityPlaceholder: TextView
    private lateinit var tvLiveIndicator: TextView

    // Live Captured SMS UI
    private lateinit var tvSmsStatusBadge: TextView
    private lateinit var tvSmsDetectionSummary: TextView
    private lateinit var tvSmsMeta: TextView
    private lateinit var tvSmsOtpCode: TextView
    private lateinit var btnCopyOtp: Button
    private lateinit var tvSmsForwardStatus: TextView
    private lateinit var tvSmsFullBody: TextView
    private lateinit var btnCheckInbox: Button
    private lateinit var btnSimulateTestSms: Button

    // History UI
    private lateinit var llSmsHistoryContainer: LinearLayout
    private lateinit var tvNoHistoryPlaceholder: TextView
    private lateinit var btnClearHistory: Button

    private val httpClient = OkHttpClient()
    private var pollTimer: Timer? = null

    private val smsBroadcastReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "com.ivac.otpforwarder.ACTION_SMS_CAPTURED" -> {
                    val smsText = intent.getStringExtra("smsText") ?: ""
                    val sender = intent.getStringExtra("sender") ?: ""
                    val simSlot = intent.getIntExtra("simSlot", 1)
                    val otpCode = intent.getStringExtra("otpCode") ?: ""
                    val timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis())
                    val status = intent.getStringExtra("status") ?: "SENDING"

                    updateCapturedSmsUI(smsText, sender, simSlot, otpCode, timestamp, status, "সার্ভারে পাঠানো হচ্ছে...")
                    loadSmsHistoryFromPrefs()
                }
                "com.ivac.otpforwarder.ACTION_SMS_STATUS_UPDATE" -> {
                    val status = intent.getStringExtra("status") ?: ""
                    val detail = intent.getStringExtra("detail") ?: ""
                    updateForwardStatusUI(status, detail)
                    loadSmsHistoryFromPrefs()
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQ_CODE = 1001
        private const val SERVER_BASE_URL = "https://ais-dev-r6fwghsme2uhic2o5q6fsu-466272014554.asia-southeast1.run.app"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("ivac_otp_prefs", Context.MODE_PRIVATE)

        // View bindings
        etSim1Phone = findViewById(R.id.etSim1Phone)
        etSim2Phone = findViewById(R.id.etSim2Phone)
        tvSim1Status = findViewById(R.id.tvSim1Status)
        tvSim2Status = findViewById(R.id.tvSim2Status)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        btnSave = findViewById(R.id.btnSave)
        btnRequestBattery = findViewById(R.id.btnRequestBattery)
        llActivityContainer = findViewById(R.id.llActivityContainer)
        tvNoActivityPlaceholder = findViewById(R.id.tvNoActivityPlaceholder)
        tvLiveIndicator = findViewById(R.id.tvLiveIndicator)

        // Live SMS Detection view bindings
        tvSmsStatusBadge = findViewById(R.id.tvSmsStatusBadge)
        tvSmsDetectionSummary = findViewById(R.id.tvSmsDetectionSummary)
        tvSmsMeta = findViewById(R.id.tvSmsMeta)
        tvSmsOtpCode = findViewById(R.id.tvSmsOtpCode)
        btnCopyOtp = findViewById(R.id.btnCopyOtp)
        tvSmsForwardStatus = findViewById(R.id.tvSmsForwardStatus)
        tvSmsFullBody = findViewById(R.id.tvSmsFullBody)
        btnCheckInbox = findViewById(R.id.btnCheckInbox)
        btnSimulateTestSms = findViewById(R.id.btnSimulateTestSms)

        // History view bindings
        llSmsHistoryContainer = findViewById(R.id.llSmsHistoryContainer)
        tvNoHistoryPlaceholder = findViewById(R.id.tvNoHistoryPlaceholder)
        btnClearHistory = findViewById(R.id.btnClearHistory)

        // Load saved phone numbers
        etSim1Phone.setText(prefs.getString("sim1_number", ""))
        etSim2Phone.setText(prefs.getString("sim2_number", ""))

        // Event listeners
        btnSave.setOnClickListener {
            val s1 = etSim1Phone.text.toString().trim()
            val s2 = etSim2Phone.text.toString().trim()

            prefs.edit()
                .putString("sim1_number", s1)
                .putString("sim2_number", s2)
                .apply()

            Toast.makeText(this, "সিম নম্বর সফলভাবে সংরক্ষণ করা হয়েছে!", Toast.LENGTH_SHORT).show()
            fetchRecentActivities()
        }

        btnCopyOtp.setOnClickListener {
            val code = tvSmsOtpCode.text.toString().trim()
            if (code.isNotBlank() && code != "------" && !code.contains("⚠️")) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("IVAC OTP", code)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "📋 ওটিপি কোড [$code] কপি করা হয়েছে!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "কপি করার মতো কোনো ওটিপি কোড নেই", Toast.LENGTH_SHORT).show()
            }
        }

        btnCheckInbox.setOnClickListener {
            checkInboxForLatestIvacSms()
        }

        btnSimulateTestSms.setOnClickListener {
            simulateTestSmsArrival()
        }

        btnClearHistory.setOnClickListener {
            prefs.edit().putString("sms_history_json", "[]").apply()
            loadSmsHistoryFromPrefs()
            Toast.makeText(this, "হিস্ট্রি মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show()
        }

        btnRequestBattery.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        // Initialize features
        checkAndRequestPermissions()
        detectSimSlots()
        startForwarderService()
        startActivityPolling()
        loadLatestSmsFromPrefs()
        loadSmsHistoryFromPrefs()
    }

    override fun onResume() {
        super.onResume()
        loadLatestSmsFromPrefs()
        loadSmsHistoryFromPrefs()

        val filter = IntentFilter().apply {
            addAction("com.ivac.otpforwarder.ACTION_SMS_CAPTURED")
            addAction("com.ivac.otpforwarder.ACTION_SMS_STATUS_UPDATE")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(this, smsBroadcastReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(smsBroadcastReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(smsBroadcastReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollTimer?.cancel()
    }

    private fun loadLatestSmsFromPrefs() {
        val smsText = prefs.getString("last_sms_text", null)
        if (smsText.isNullOrBlank()) {
            tvSmsStatusBadge.text = "⏳ অপেক্ষমাণ"
            tvSmsStatusBadge.setTextColor(Color.parseColor("#94A3B8"))
            tvSmsDetectionSummary.text = "মেসেজ ডিটেকশন ইঞ্জিন প্রস্তুত। আইভ্যাক বা এক্সটেনশন থেকে মেসেজ আসলে স্বয়ংক্রিয়ভাবে এখানে দেখা যাবে।"
            tvSmsDetectionSummary.setTextColor(Color.parseColor("#94A3B8"))
            tvSmsMeta.text = "📶 সিম: রেডি • প্রেরক: অপেক্ষমাণ • রিয়েল-টাইম ডিটেকশন সক্রিয়"
            tvSmsOtpCode.text = "------"
            tvSmsOtpCode.setTextColor(Color.parseColor("#94A3B8"))
            tvSmsForwardStatus.text = "সার্ভার স্ট্যাটাস: রেডি (অপেক্ষমাণ)"
            tvSmsForwardStatus.setTextColor(Color.parseColor("#94A3B8"))
            tvSmsFullBody.text = "এখনও কোনো এসএমএস আসেনি। নতুন এসএমএস এলে পুরো বার্তাটি এখানে তাৎক্ষণিক দেখা যাবে..."
            return
        }

        val sender = prefs.getString("last_sms_sender", "অজ্ঞাত") ?: "অজ্ঞাত"
        val simSlot = prefs.getInt("last_sms_sim_slot", 1)
        val otpCode = prefs.getString("last_sms_otp", "") ?: ""
        val timestamp = prefs.getLong("last_sms_timestamp", System.currentTimeMillis())
        val status = prefs.getString("last_sms_forward_status", "SUCCESS") ?: "SUCCESS"
        val detail = prefs.getString("last_sms_status_detail", "সফল") ?: "সফল"

        updateCapturedSmsUI(smsText, sender, simSlot, otpCode, timestamp, status, detail)
    }

    private fun updateCapturedSmsUI(
        smsText: String,
        sender: String,
        simSlot: Int,
        otpCode: String,
        timestamp: Long,
        status: String,
        detail: String
    ) {
        val sdf = SimpleDateFormat("hh:mm:ss a, dd MMM", Locale.getDefault())
        val timeStr = sdf.format(Date(timestamp))

        val senderDisplay = if (sender.isNotBlank()) sender else "IVAC"
        val isIvacRelated = smsText.contains("ivac", ignoreCase = true) ||
                smsText.contains("visa", ignoreCase = true) ||
                sender.contains("ivac", ignoreCase = true)

        if (otpCode.isNotBlank()) {
            if (isIvacRelated) {
                tvSmsStatusBadge.text = "🟢 আইভ্যাক ওটিপি ডিটেক্টেড"
                tvSmsStatusBadge.setTextColor(Color.parseColor("#34D399"))
                tvSmsDetectionSummary.text = "✅ আইভ্যাকের ওটিপি মেসেজ সফলভাবে সনাক্ত ও ক্যাপচার করা হয়েছে!"
                tvSmsDetectionSummary.setTextColor(Color.parseColor("#34D399"))
            } else {
                tvSmsStatusBadge.text = "🟢 ওটিপি সনাক্ত হয়েছে"
                tvSmsStatusBadge.setTextColor(Color.parseColor("#38BDF8"))
                tvSmsDetectionSummary.text = "✅ মেসেজ থেকে ওটিপি কোড সফলভাবে ডিটেক্ট করা হয়েছে!"
                tvSmsDetectionSummary.setTextColor(Color.parseColor("#38BDF8"))
            }

            tvSmsOtpCode.text = otpCode
            tvSmsOtpCode.setTextColor(Color.parseColor("#34D399"))
        } else {
            tvSmsStatusBadge.text = "⚠️ ওটিপি বিহীন মেসেজ"
            tvSmsStatusBadge.setTextColor(Color.parseColor("#F59E0B"))
            tvSmsDetectionSummary.text = "⚠️ মেসেজ রিসিভ হয়েছে, তবে এতে কোনো ওটিপি কোড সনাক্ত করা যায়নি।"
            tvSmsDetectionSummary.setTextColor(Color.parseColor("#F59E0B"))

            tvSmsOtpCode.text = "⚠️ কোড নেই"
            tvSmsOtpCode.setTextColor(Color.parseColor("#F59E0B"))
        }

        tvSmsMeta.text = "📶 সিম $simSlot • প্রেরক: $senderDisplay • সময়: $timeStr"
        tvSmsFullBody.text = smsText

        updateForwardStatusUI(status, detail)
    }

    private fun updateForwardStatusUI(status: String, detail: String) {
        when (status) {
            "SUCCESS" -> {
                tvSmsForwardStatus.text = "✅ ক্লাউড সার্ভারে পৌঁছেছে ➔ ব্রাউজার এক্সটেনশন অটোফিলে প্রস্তুত ($detail)"
                tvSmsForwardStatus.setTextColor(Color.parseColor("#A7F3D0"))
            }
            "SENDING" -> {
                tvSmsForwardStatus.text = "⏳ সার্ভারে পাঠানো হচ্ছে... ($detail)"
                tvSmsForwardStatus.setTextColor(Color.parseColor("#FDE68A"))
            }
            "FAILED" -> {
                tvSmsForwardStatus.text = "❌ সার্ভারে পাঠাতে ব্যর্থ: $detail"
                tvSmsForwardStatus.setTextColor(Color.parseColor("#FCA5A5"))
            }
            "NO_OTP" -> {
                tvSmsForwardStatus.text = "⚠️ ওটিপি কোড না থাকায় সার্ভারে ফরোয়ার্ড করা হয়নি"
                tvSmsForwardStatus.setTextColor(Color.parseColor("#FCD34D"))
            }
            else -> {
                tvSmsForwardStatus.text = detail.ifBlank { "স্ট্যাটাস: $status" }
                tvSmsForwardStatus.setTextColor(Color.parseColor("#CBD5E1"))
            }
        }
    }

    /**
     * Checks phone's SMS Inbox directly using ContentResolver.
     * Guarantees detection even if phone was asleep or background receiver had OEM delay!
     */
    private fun checkInboxForLatestIvacSms() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS), PERMISSION_REQ_CODE)
            Toast.makeText(this, "মেসেজ পড়ার জন্য READ_SMS পারমিশন প্রয়োজন", Toast.LENGTH_SHORT).show()
            return
        }

        var cursor: Cursor? = null
        try {
            val inboxUri = Uri.parse("content://sms/inbox")
            cursor = contentResolver.query(
                inboxUri,
                arrayOf("_id", "address", "body", "date", "sub_id"),
                null,
                null,
                "date DESC LIMIT 10"
            )

            if (cursor != null && cursor.moveToFirst()) {
                var foundCandidate: Boolean = false

                do {
                    val address = cursor.getString(cursor.getColumnIndexOrThrow("address")) ?: ""
                    val body = cursor.getString(cursor.getColumnIndexOrThrow("body")) ?: ""
                    val date = cursor.getLong(cursor.getColumnIndexOrThrow("date"))

                    val isIvac = body.contains("ivac", ignoreCase = true) ||
                            body.contains("visa", ignoreCase = true) ||
                            address.contains("ivac", ignoreCase = true)

                    val otp = SmsOtpReceiver.extractOtpCode(body)

                    if (isIvac || !otp.isNullOrBlank()) {
                        foundCandidate = true

                        // Save and update UI
                        val simSlot = 1
                        val timeNow = if (date > 0) date else System.currentTimeMillis()
                        val initStatus = if (!otp.isNullOrBlank()) "SENDING" else "NO_OTP"

                        prefs.edit()
                            .putString("last_sms_text", body)
                            .putString("last_sms_sender", address)
                            .putInt("last_sms_sim_slot", simSlot)
                            .putString("last_sms_otp", otp ?: "")
                            .putLong("last_sms_timestamp", timeNow)
                            .putString("last_sms_forward_status", initStatus)
                            .putString("last_sms_status_detail", "ইনবক্স থেকে পাওয়া গেছে")
                            .apply()

                        SmsOtpReceiver.saveSmsToHistory(this, body, address, simSlot, otp ?: "", timeNow, initStatus)

                        updateCapturedSmsUI(body, address, simSlot, otp ?: "", timeNow, initStatus, "ইনবক্স থেকে ডিটেক্টেড")
                        loadSmsHistoryFromPrefs()

                        if (!otp.isNullOrBlank()) {
                            val targetPhone = prefs.getString("sim1_number", "auto")?.ifBlank { "auto" } ?: "auto"
                            forwardOtpManually(targetPhone, otp, simSlot, address, body)
                        }

                        Toast.makeText(this, "✅ ইনবক্স থেকে সর্বশেষ ওটিপি মেসেজ ডিটেক্ট করা হয়েছে!", Toast.LENGTH_SHORT).show()
                        break
                    }
                } while (cursor.moveToNext())

                if (!foundCandidate) {
                    // Fallback to absolute newest SMS if no IVAC found
                    cursor.moveToFirst()
                    val address = cursor.getString(cursor.getColumnIndexOrThrow("address")) ?: ""
                    val body = cursor.getString(cursor.getColumnIndexOrThrow("body")) ?: ""
                    val date = cursor.getLong(cursor.getColumnIndexOrThrow("date"))
                    val otp = SmsOtpReceiver.extractOtpCode(body) ?: ""

                    updateCapturedSmsUI(body, address, 1, otp, date, "NO_OTP", "ইনবক্সের সর্বশেষ সাধারণ বার্তা")
                    Toast.makeText(this, "ইনবক্সে নতুন কোনো IVAC মেসেজ মেলেনি, সর্বশেষ বার্তাটি দেখানো হলো।", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "ইনবক্সে কোনো এসএমএস খুঁজে পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query SMS inbox", e)
            Toast.makeText(this, "ইনবক্স চেক ত্রুটি: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        } finally {
            cursor?.close()
        }
    }

    private fun loadSmsHistoryFromPrefs() {
        val historyJson = prefs.getString("sms_history_json", "[]") ?: "[]"
        try {
            val jsonArray = JSONArray(historyJson)
            llSmsHistoryContainer.removeAllViews()

            if (jsonArray.length() == 0) {
                tvNoHistoryPlaceholder.visibility = View.VISIBLE
                llSmsHistoryContainer.addView(tvNoHistoryPlaceholder)
                return
            }

            tvNoHistoryPlaceholder.visibility = View.GONE
            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val smsText = item.optString("smsText", "")
                val sender = item.optString("sender", "IVAC")
                val simSlot = item.optInt("simSlot", 1)
                val otpCode = item.optString("otpCode", "")
                val timestamp = item.optLong("timestamp", System.currentTimeMillis())
                val timeStr = sdf.format(Date(timestamp))

                val itemView = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(12, 10, 12, 10)
                    setBackgroundColor(Color.parseColor(if (i % 2 == 0) "#0F172A" else "#131E35"))
                    isClickable = true
                    isFocusable = true

                    setOnClickListener {
                        updateCapturedSmsUI(smsText, sender, simSlot, otpCode, timestamp, "SUCCESS", "হিস্ট্রি থেকে দেখা হচ্ছে")
                        Toast.makeText(this@MainActivity, "মেসেজটি বিস্তারিত স্ক্রিনে লোড করা হয়েছে", Toast.LENGTH_SHORT).show()
                    }
                }

                val rowHeader = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

                val tvItemTitle = TextView(this).apply {
                    text = "📶 সিম $simSlot • $sender"
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor("#38BDF8"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val tvItemTime = TextView(this).apply {
                    text = timeStr
                    textSize = 10f
                    setTextColor(Color.parseColor("#94A3B8"))
                }

                rowHeader.addView(tvItemTitle)
                rowHeader.addView(tvItemTime)
                itemView.addView(rowHeader)

                val tvItemOtp = TextView(this).apply {
                    text = if (otpCode.isNotBlank()) "🔑 OTP: $otpCode" else "ℹ️ সাধারণ বার্তা"
                    textSize = 11f
                    setTextColor(Color.parseColor(if (otpCode.isNotBlank()) "#34D399" else "#94A3B8"))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 4, 0, 2)
                }
                itemView.addView(tvItemOtp)

                val tvItemSnippet = TextView(this).apply {
                    text = if (smsText.length > 70) smsText.substring(0, 70) + "..." else smsText
                    textSize = 11f
                    setTextColor(Color.parseColor("#CBD5E1"))
                }
                itemView.addView(tvItemSnippet)

                llSmsHistoryContainer.addView(itemView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error displaying SMS history", e)
        }
    }

    private fun simulateTestSmsArrival() {
        val testOtp = "" + (100000 + (Math.random() * 899999).toInt())
        val testSender = "IVAC-BD"
        val testMsg = "Dear Applicant, your IVAC visa appointment login OTP is $testOtp. Valid for 5 minutes. Do not share with anyone."
        val timeNow = System.currentTimeMillis()

        prefs.edit()
            .putString("last_sms_text", testMsg)
            .putString("last_sms_sender", testSender)
            .putInt("last_sms_sim_slot", 1)
            .putString("last_sms_otp", testOtp)
            .putLong("last_sms_timestamp", timeNow)
            .putString("last_sms_forward_status", "SENDING")
            .putString("last_sms_status_detail", "টেস্ট ওটিপি পুশ হচ্ছে...")
            .apply()

        SmsOtpReceiver.saveSmsToHistory(this, testMsg, testSender, 1, testOtp, timeNow, "SENDING")

        updateCapturedSmsUI(testMsg, testSender, 1, testOtp, timeNow, "SENDING", "টেস্ট ওটিপি পুশ হচ্ছে...")
        loadSmsHistoryFromPrefs()
        Toast.makeText(this, "🧪 টেস্ট মেসেজ ডিটেক্টেড! ওটিপি: $testOtp", Toast.LENGTH_SHORT).show()

        val targetPhone = prefs.getString("sim1_number", "auto")?.ifBlank { "auto" } ?: "auto"
        forwardOtpManually(targetPhone, testOtp, 1, testSender, testMsg)
    }

    private fun forwardOtpManually(
        targetPhone: String,
        otpCode: String,
        simSlot: Int,
        sender: String,
        fullMsg: String
    ) {
        val customServerUrl = prefs.getString("server_url", "$SERVER_BASE_URL/api/otp/push") ?: "$SERVER_BASE_URL/api/otp/push"

        val json = JSONObject().apply {
            put("phoneNumber", targetPhone)
            put("otpCode", otpCode)
            put("simSlot", simSlot)
            put("simName", if (simSlot == 2) "SIM 2" else "SIM 1")
            put("rawSender", sender)
            put("rawText", fullMsg)
        }

        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(customServerUrl).post(requestBody).build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    updateForwardStatusUI("FAILED", e.localizedMessage ?: "নেটওয়ার্ক কানেকশন ফেইল্ড")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    runOnUiThread {
                        if (response.isSuccessful) {
                            updateForwardStatusUI("SUCCESS", "HTTP ${response.code} (সফল)")
                        } else {
                            updateForwardStatusUI("FAILED", "HTTP ${response.code} সার্ভার ত্রুটি")
                        }
                    }
                }
            }
        })
    }

    private fun startActivityPolling() {
        pollTimer?.cancel()
        pollTimer = Timer()
        pollTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                fetchRecentActivities()
            }
        }, 1000, 3500)
    }

    private fun fetchRecentActivities() {
        val url = "$SERVER_BASE_URL/api/activity/history"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    tvLiveIndicator.text = "🟡 RECONNECTING"
                    tvLiveIndicator.setTextColor(Color.parseColor("#FBBF24"))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return
                        try {
                            val jsonArray = JSONArray(body)
                            runOnUiThread {
                                tvLiveIndicator.text = "🟢 LIVE"
                                tvLiveIndicator.setTextColor(Color.parseColor("#34D399"))
                                updateActivityUI(jsonArray)
                            }
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                }
            }
        })
    }

    private fun updateActivityUI(activities: JSONArray) {
        if (activities.length() == 0) {
            tvNoActivityPlaceholder.visibility = View.VISIBLE
            return
        }

        tvNoActivityPlaceholder.visibility = View.GONE
        llActivityContainer.removeAllViews()

        val maxItems = 4
        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())

        for (i in 0 until minOf(activities.length(), maxItems)) {
            val act = activities.getJSONObject(i)
            val type = act.optString("type", "INFO")
            val title = act.optString("title", "অ্যাক্টিভিটি")
            val message = act.optString("message", "")
            val timestamp = act.optLong("timestamp", System.currentTimeMillis())
            val phone = act.optString("phone", "")
            val timeStr = sdf.format(Date(timestamp))

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 10, 12, 10)
                setBackgroundColor(Color.parseColor(if (i % 2 == 0) "#0F172A" else "#131E35"))
            }

            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val tvTitle = TextView(this).apply {
                text = title
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                val tColor = when (type) {
                    "LOGIN_SUCCESS" -> "#34D399"
                    "FILE_UPLOADED" -> "#A5B4FC"
                    "SLOT_RELEASED" -> "#6EE7B7"
                    "PAYMENT_INITIATED" -> "#FDBA74"
                    else -> "#93C5FD"
                }
                setTextColor(Color.parseColor(tColor))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvTime = TextView(this).apply {
                text = timeStr
                textSize = 10f
                setTextColor(Color.parseColor("#94A3B8"))
            }

            headerRow.addView(tvTitle)
            headerRow.addView(tvTime)
            card.addView(headerRow)

            val tvMsg = TextView(this).apply {
                text = message + (if (phone.isNotBlank()) " • $phone" else "")
                textSize = 11f
                setTextColor(Color.parseColor("#CBD5E1"))
                setPadding(0, 4, 0, 0)
            }
            card.addView(tvMsg)

            llActivityContainer.addView(card)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val ungranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, ungranted.toTypedArray(), PERMISSION_REQ_CODE)
        }
    }

    private fun detectSimSlots() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            val subManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subList: List<SubscriptionInfo>? = subManager.activeSubscriptionInfoList

            if (!subList.isNullOrEmpty()) {
                for (info in subList) {
                    val slot = info.simSlotIndex + 1
                    val carrier = info.carrierName ?: "Carrier"
                    if (slot == 1) {
                        tvSim1Status.text = "📶 সিম ১ সক্রিয় ($carrier)"
                    } else if (slot == 2) {
                        tvSim2Status.text = "📶 সিম ২ সক্রিয় ($carrier)"
                    }
                }
            }
        } catch (e: SecurityException) {
            // handle
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "ব্যাটারি অপ্টিমাইজেশন ইতিমধ্যে বন্ধ রয়েছে (Unrestricted)!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startForwarderService() {
        val serviceIntent = Intent(this, OtpForwarderService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        tvServiceStatus.text = "সার্ভিস স্ট্যাটাস: সার্বক্ষণিক সক্রিয় (Active)"
    }
}
