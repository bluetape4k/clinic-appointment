package io.bluetape4k.clinic.appointment.api.auth

/** 환자 인증 경계에서 외부로 전달하지 않는 내부 예외의 공통 타입입니다. */
open class PatientAuthenticationException(message: String) : RuntimeException(message)

/** 입력 validation 실패입니다. raw credential이나 identifier는 message에 포함하지 않습니다. */
class PatientAuthenticationValidationException(message: String = "patient authentication input is invalid") :
    PatientAuthenticationException(message)

/** tenant path가 활성 tenant를 가리키지 않습니다. */
class PatientTenantNotFoundException : PatientAuthenticationException("patient tenant is unavailable")

/** 이미 사용 중인 tenant-scoped identifier입니다. */
class PatientDuplicateIdentifierException : PatientAuthenticationException("patient identifier is unavailable")

/** identifier 존재 여부와 password 실패를 구분하지 않는 public credentials failure입니다. */
class PatientInvalidCredentialsException : PatientAuthenticationException("invalid patient credentials")

/** edge/application limiter가 login 시도를 거절했습니다. */
class PatientLoginRateLimitedException : PatientAuthenticationException("patient login is temporarily unavailable")
