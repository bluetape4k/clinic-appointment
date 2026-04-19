export interface TreatmentType {
  id: number;
  clinicId: number;
  name: string;
  category: string;
  defaultDurationMinutes: number;
  requiredProviderType: string;
  consultationMethod?: string;
  requiresEquipment: boolean;
  maxConcurrentPatients?: number;
}
