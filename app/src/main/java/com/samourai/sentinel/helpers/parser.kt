package com.samourai.sentinel.helpers

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import timber.log.Timber
import java.lang.reflect.Type


// #555: same mode as the deprecated GsonBuilder.setLenient(). Dojo and
// wallet JSON stays on Strictness.LENIENT. fromJson uses JsonReader's
// default nesting limit (255 since Gson 2.12): a deeper document throws
// JsonSyntaxException, which fromJSON catches. A lower cap is
// JsonReader.setNestingLimit on a reader handed to fromJson.
val gson: Gson = GsonBuilder()
        .setStrictness(Strictness.LENIENT)
        .create()

fun Any.toJSON(): String? {
    return try {
        Gson().toJson(this)
    } catch (Ex: Exception) {
        null
    }
}


inline fun <reified T> fromJSON(payload: String, type: Type? = null): T? {
    return try {
        return if (type != null) {
            gson.fromJson<T>(payload, type)
        } else {
            gson.fromJson(payload, T::class.java)
        }
    } catch (Ex: Exception) {
        Ex.printStackTrace()
        null
    }
}