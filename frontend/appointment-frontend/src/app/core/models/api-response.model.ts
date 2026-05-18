export interface ApiResponse<T> {
  success: boolean;
  data?: T;
  error?: string;
}

export interface PagedData<T> {
  content: T[];
  totalCount: number;
  pageNumber: number;
  pageSize: number;
  totalPages: number;
  isFirst: boolean;
  isLast: boolean;
  hasNext: boolean;
  hasPrevious: boolean;
}
