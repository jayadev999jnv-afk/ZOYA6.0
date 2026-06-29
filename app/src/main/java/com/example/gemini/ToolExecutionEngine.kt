package com.example.gemini

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.ZoyaStateController
import kotlinx.coroutines.flow.first

class ToolExecutionEngine(private val context: Context) {

    fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun executeTool(name: String, argsJson: org.json.JSONObject): org.json.JSONObject {
        val response = org.json.JSONObject()
        try {
            when (name) {
                "openApp" -> {
                    val packageName = argsJson.optString("packageName", "")
                    if (packageName.isEmpty()) {
                        response.put("success", false)
                        response.put("error", "packageName is required")
                    } else {
                        val opened = openApp(packageName)
                        response.put("success", opened)
                        response.put("message", if (opened) "Successfully opened $packageName" else "Failed to open $packageName")
                    }
                }
                "searchAndCallContact" -> {
                    val contactName = argsJson.optString("contactName", "")
                    if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
                        ZoyaStateController.actionEvent.emit("permission_denied:${Manifest.permission.READ_CONTACTS}")
                        response.put("success", false)
                        response.put("error", "Permission READ_CONTACTS is not granted. Please ask the user sassily to enable contacts permission.")
                    } else if (!hasPermission(Manifest.permission.CALL_PHONE)) {
                        ZoyaStateController.actionEvent.emit("permission_denied:${Manifest.permission.CALL_PHONE}")
                        response.put("success", false)
                        response.put("error", "Permission CALL_PHONE is not granted. Please ask the user sassily to enable phone call permission.")
                    } else {
                        val number = findContactNumber(contactName)
                        if (number != null) {
                            val called = makePhoneCall(number)
                            response.put("success", called)
                            response.put("message", "Triggered call to $contactName at $number")
                        } else {
                            response.put("success", false)
                            response.put("error", "Contact '$contactName' was not found in your address book.")
                        }
                    }
                }
                "sendWhatsAppMessage" -> {
                    val contactName = argsJson.optString("contactName", "")
                    val message = argsJson.optString("message", "")
                    if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
                        ZoyaStateController.actionEvent.emit("permission_denied:${Manifest.permission.READ_CONTACTS}")
                        response.put("success", false)
                        response.put("error", "Permission READ_CONTACTS is not granted. Please ask the user sassily to enable contacts permission.")
                    } else {
                        val number = findContactNumber(contactName)
                        if (number != null) {
                            val sent = sendWhatsApp(number, message)
                            response.put("success", sent)
                            response.put("message", "Deep-linked to WhatsApp for $contactName")
                        } else {
                            response.put("success", false)
                            response.put("error", "Contact '$contactName' was not found in your address book.")
                        }
                    }
                }
                "sendGmail" -> {
                    val recipientEmail = argsJson.optString("recipientEmail", "")
                    val subject = argsJson.optString("subject", "")
                    val body = argsJson.optString("body", "")
                    val sent = sendGmail(recipientEmail, subject, body)
                    response.put("success", sent)
                    response.put("message", if (sent) "Email intent prepared successfully" else "Failed to open email client")
                }
                else -> {
                    response.put("success", false)
                    response.put("error", "Unknown tool function: $name")
                }
            }
        } catch (e: Exception) {
            response.put("success", false)
            response.put("error", "Exception in tool execution: ${e.message}")
        }
        return response
    }

    private fun openApp(appNameOrPackage: String): Boolean {
        val packageMap = mapOf(
            "youtube" to "com.google.android.youtube",
            "instagram" to "com.instagram.android",
            "chrome" to "com.android.chrome",
            "calculator" to "com.google.android.calculator",
            "whatsapp" to "com.whatsapp",
            "gmail" to "com.google.android.gm"
        )
        val packageName = packageMap[appNameOrPackage.lowercase()] ?: appNameOrPackage
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } else {
            // Fallback: search Play Store
            try {
                val playIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(playIntent)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun findContactNumber(name: String): String? {
        val contentResolver = context.contentResolver
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numIdx >= 0) {
                    return it.getString(numIdx)
                }
            }
        }
        return null
    }

    private fun makePhoneCall(number: String): Boolean {
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$number")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun sendWhatsApp(number: String, message: String): Boolean {
        val cleanNumber = number.filter { it.isDigit() }
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(message)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun sendGmail(recipient: String, subject: String, body: String): Boolean {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
