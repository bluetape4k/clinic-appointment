export interface AppointmentDisplayContext {
  productName?: string | null;
  sessionNumber?: number | null;
  totalSessions?: number | null;
}

export function resolveAppointmentTitle(context: AppointmentDisplayContext, fallback: string): string {
  const productName = context.productName?.trim();
  return productName || fallback;
}

export function formatAppointmentSession(context: AppointmentDisplayContext): string | null {
  const sessionNumber = context.sessionNumber;
  if (!Number.isInteger(sessionNumber) || sessionNumber == null || sessionNumber <= 0) {
    return null;
  }
  const totalSessions = context.totalSessions;
  if (Number.isInteger(totalSessions) && totalSessions != null && totalSessions > 0) {
    return `${sessionNumber}회차 / ${totalSessions}회`;
  }
  return `${sessionNumber}회차`;
}
