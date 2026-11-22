package com.manh.panelsassistive

import java.util.Calendar
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * Vietnamese lunar calendar (UTC+7) using Hồ Ngọc Đức's well-known astronomical
 * algorithm. Only what the clock widget needs: convert the device's current
 * solar date and render it as "(dd/mm - ÂL)".
 */
object LunarCalendar {
    private const val TIMEZONE = 7.0

    private data class Date(val day: Int, val month: Int, val leap: Boolean)

    fun todayLabel(): String {
        val now = Calendar.getInstance()
        val lunar = convert(
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH)
        )
        return buildString {
            append('(')
            append(lunar.day)
            append('/')
            append(lunar.month)
            if (lunar.leap) append(" nhuận")
            append(" - ÂL)")
        }
    }

    private fun convert(year: Int, month: Int, day: Int): Date {
        val dayNumber = jdFromDate(day, month, year)
        val k = floor((dayNumber - 2415021.076998695) / 29.530588853).toInt()
        var monthStart = newMoonDay(k + 1)
        if (monthStart > dayNumber) monthStart = newMoonDay(k)
        var a11 = lunarMonth11(year)
        var b11 = a11
        if (a11 >= monthStart) {
            a11 = lunarMonth11(year - 1)
        } else {
            b11 = lunarMonth11(year + 1)
        }
        val lunarDay = (dayNumber - monthStart + 1).toInt()
        val diff = ((monthStart - a11) / 29).toInt()
        var lunarMonth = diff + 11
        var leap = false
        if (b11 - a11 > 365) {
            val leapMonth = leapMonthOffset(a11)
            if (diff >= leapMonth) {
                lunarMonth = diff + 10
                leap = diff == leapMonth
            }
        }
        if (lunarMonth > 12) lunarMonth -= 12
        return Date(lunarDay, lunarMonth, leap)
    }

    /** Julian day number for a Gregorian date. */
    private fun jdFromDate(day: Int, month: Int, year: Int): Long {
        val a = (14 - month) / 12
        val y = year + 4800 - a
        val m = month + 12 * a - 3
        var jd = day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045
        if (jd < 2299161) {
            jd = day + (153 * m + 2) / 5 + 365 * y + y / 4 - 32083
        }
        return jd.toLong()
    }

    /** Julian day (UTC+7 midnight) of the k-th new moon after 1900. */
    private fun newMoonDay(k: Int): Long {
        val t = k / 1236.85
        val t2 = t * t
        val t3 = t2 * t
        val dr = PI / 180
        var jd = 2415020.75933 + 29.53058868 * k + 0.0001178 * t2 - 0.000000155 * t3
        jd += 0.00033 * sin((166.56 + 132.87 * t - 0.009173 * t2) * dr)
        val m = 359.2242 + 29.10535608 * k - 0.0000333 * t2 - 0.00000347 * t3
        val mpr = 306.0253 + 385.81691806 * k + 0.0107306 * t2 + 0.00001236 * t3
        val f = 21.2964 + 390.67050646 * k - 0.0016528 * t2 - 0.00000239 * t3
        var c = (0.1734 - 0.000393 * t) * sin(m * dr) + 0.0021 * sin(2 * dr * m)
        c -= 0.4068 * sin(mpr * dr)
        c += 0.0161 * sin(2 * dr * mpr)
        c -= 0.0004 * sin(3 * dr * mpr)
        c += 0.0104 * sin(2 * dr * f)
        c -= 0.0051 * sin((m + mpr) * dr)
        c -= 0.0074 * sin((m - mpr) * dr)
        c += 0.0004 * sin((2 * f + m) * dr)
        c -= 0.0004 * sin((2 * f - m) * dr)
        c -= 0.0006 * sin((2 * f + mpr) * dr)
        c += 0.0010 * sin((2 * f - mpr) * dr)
        c += 0.0005 * sin((m + 2 * mpr) * dr)
        val deltaT = if (t < -11) {
            0.001 + 0.000839 * t + 0.0002261 * t2 - 0.00000845 * t3 -
                0.000000081 * t * t3
        } else {
            -0.000278 + 0.000265 * t + 0.000262 * t2
        }
        return floor(jd + c - deltaT + 0.5 + TIMEZONE / 24).toLong()
    }

    /** Sun's ecliptic longitude in 30° sectors (0..11) for a Julian day. */
    private fun sunLongitude(dayNumber: Long): Int {
        val jdn = dayNumber - 0.5 - TIMEZONE / 24
        val t = (jdn - 2451545.0) / 36525
        val t2 = t * t
        val dr = PI / 180
        val m = 357.52910 + 35999.05030 * t - 0.0001559 * t2 - 0.00000048 * t * t2
        val l0 = 280.46645 + 36000.76983 * t + 0.0003032 * t2
        var dl = (1.914600 - 0.004817 * t - 0.000014 * t2) * sin(m * dr)
        dl += (0.019993 - 0.000101 * t) * sin(2 * m * dr) + 0.000290 * sin(3 * m * dr)
        var l = (l0 + dl) * dr
        l -= 2 * PI * floor(l / (2 * PI))
        return floor(l / PI * 6).toInt()
    }

    /** Julian day of the start of the 11th lunar month of a solar year. */
    private fun lunarMonth11(year: Int): Long {
        val off = jdFromDate(31, 12, year) - 2415021
        val k = (off / 29.530588853).toInt()
        var nm = newMoonDay(k)
        if (sunLongitude(nm) >= 9) nm = newMoonDay(k - 1)
        return nm
    }

    private fun leapMonthOffset(a11: Long): Int {
        val k = ((a11 - 2415021.076998695) / 29.530588853 + 0.5).toInt()
        var i = 1
        var last = 0
        var arc = sunLongitude(newMoonDay(k + i))
        do {
            last = arc
            i++
            arc = sunLongitude(newMoonDay(k + i))
        } while (arc != last && i < 14)
        return i - 1
    }
}
