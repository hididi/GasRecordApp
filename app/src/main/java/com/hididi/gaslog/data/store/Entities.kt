package com.hididi.gaslog.data.store

/** 一次抄表记录（拍照/相册导入/手工录入都会落成一条） */
data class MeterReadingEntity(
    val id: Long = 0,
    /** 拍摄时间（EXIF 优先，其次相册记录时间，最后是导入时刻） */
    val epochMillis: Long,
    /** 表数，单位 0.001 m³ */
    val readingMilli: Long,
    val photoPath: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationName: String? = null,
    /** 拍摄地点当天的温度区间 */
    val tempMinC: Double? = null,
    val tempMaxC: Double? = null,
    /** 温度状态：OK / NOT_FETCHED / NO_LOCATION / MANUAL / FAILED */
    val weatherStatus: String = "NOT_FETCHED",
    /** CAMERA / GALLERY / MANUAL */
    val source: String = "MANUAL",
    val ocrRaw: String? = null,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 阶梯气价的一档 */
data class TariffTierEntity(
    val tierNo: Int,
    /** 年度累计用气量上限（m³）；null 表示最后一档不限量 */
    val capM3: Double? = null,
    val priceCentsPerM3: Long,
)

/** 手工填写的交费记录：交费时读数 / 所在档位 / 本档剩余气量 */
data class PaymentEntity(
    val id: Long = 0,
    val paidAtMillis: Long,
    val readingMilli: Long,
    val amountCents: Long? = null,
    val tierNo: Int,
    val remainingM3: Double? = null,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)