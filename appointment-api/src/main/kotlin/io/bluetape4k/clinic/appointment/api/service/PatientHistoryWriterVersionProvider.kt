package io.bluetape4k.clinic.appointment.api.service

/** 모든 cancellation writer replica가 snapshot contract를 기록하는지 확인하는 외부 권위입니다. */
fun interface PatientHistoryWriterVersionProvider {
    /** 관찰된 모든 writer replica의 최소 contract version을 반환합니다. */
    fun minimumVersion(): Int

    companion object {
        const val REQUIRED_VERSION = 2
    }
}
