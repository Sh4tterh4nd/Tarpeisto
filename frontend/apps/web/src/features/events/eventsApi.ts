import { AppError, apiClient, toAppError } from "@bigcontainers/api-client";
import type { components, ProblemDetails } from "@bigcontainers/api-client";

export type BookingLineRecord = Required<components["schemas"]["BookingLineResponse"]>;
export type BookingRecord = Omit<Required<components["schemas"]["BookingResponse"]>, "lines"> & {
  lines: BookingLineRecord[];
};
export type BookingConflict = Required<components["schemas"]["BookingConflictResponse"]>;
export type BookingPreview = Required<
  components["schemas"]["BookingReservationPreviewResponse"]
> & {
  conflicts: BookingConflict[];
  warnings: BookingConflict[];
};
export type BookingHistory = Required<components["schemas"]["BookingHistoryResponse"]>;
export type BookingLineInput = components["schemas"]["CreateBookingLineRequest"];
export type BookingInput = components["schemas"]["CreateBookingRequest"];
export type CheckoutInput = components["schemas"]["CheckoutBookingRequest"];
export type CheckoutManifestAsset = Required<
  components["schemas"]["CheckoutManifestAssetResponse"]
>;
export type CheckoutManifestConsumable = Required<
  components["schemas"]["CheckoutManifestConsumableResponse"]
>;
export type AuditTask = Required<components["schemas"]["AuditTaskResponse"]>;
export type CheckoutManifest = Omit<
  Required<components["schemas"]["CheckoutManifestResponse"]>,
  "assets" | "consumables" | "auditTasks"
> & {
  assets: CheckoutManifestAsset[];
  consumables: CheckoutManifestConsumable[];
  auditTasks: AuditTask[];
};

export type ApiResult<T> = { kind: "ok"; data: T } | { kind: "error"; error: AppError };
interface Outcome {
  data?: unknown;
  error?: ProblemDetails;
  response: Response;
}

async function read<T>(operation: () => Promise<Outcome>): Promise<ApiResult<T>> {
  try {
    const { data, error, response } = await operation();
    if (error || !response.ok || data === undefined) {
      return { kind: "error", error: toAppError(error, response.status) };
    }
    return { kind: "ok", data: data as T };
  } catch (cause) {
    return { kind: "error", error: AppError.network(cause) };
  }
}

export function eventErrorMessage(error: AppError): string {
  return error.problem?.detail ?? error.message;
}

export function listBookings(from: string, until: string): Promise<ApiResult<BookingRecord[]>> {
  return read(() =>
    apiClient.GET("/api/v1/bookings", { params: { query: { from, until, limit: 100 } } }),
  );
}
export function getBooking(bookingId: string): Promise<ApiResult<BookingRecord>> {
  return read(() =>
    apiClient.GET("/api/v1/bookings/{bookingId}", { params: { path: { bookingId } } }),
  );
}
export function getBookingHistory(bookingId: string): Promise<ApiResult<BookingHistory[]>> {
  return read(() =>
    apiClient.GET("/api/v1/bookings/{bookingId}/history", {
      params: { path: { bookingId }, query: { limit: 100 } },
    }),
  );
}
export function createBooking(input: BookingInput): Promise<ApiResult<BookingRecord>> {
  return read(() => apiClient.POST("/api/v1/bookings", { body: input }));
}
export function updateBooking(
  bookingId: string,
  expectedVersion: number,
  booking: BookingInput,
): Promise<ApiResult<BookingRecord>> {
  return read(() =>
    apiClient.PUT("/api/v1/bookings/{bookingId}", {
      params: { path: { bookingId } },
      body: { expectedVersion, booking },
    }),
  );
}
export function addBookingLine(
  bookingId: string,
  input: BookingLineInput,
): Promise<ApiResult<BookingRecord>> {
  return read(() =>
    apiClient.POST("/api/v1/bookings/{bookingId}/lines", {
      params: { path: { bookingId } },
      body: input,
    }),
  );
}
export function removeBookingLine(
  bookingId: string,
  lineId: string,
  expectedVersion: number,
): Promise<ApiResult<BookingRecord>> {
  return read(() =>
    apiClient.DELETE("/api/v1/bookings/{bookingId}/lines/{lineId}", {
      params: { path: { bookingId, lineId } },
      body: { expectedVersion },
    }),
  );
}
export function previewBooking(bookingId: string): Promise<ApiResult<BookingPreview>> {
  return read(() =>
    apiClient.POST("/api/v1/bookings/{bookingId}/reservation-preview", {
      params: { path: { bookingId } },
    }),
  );
}
export function reserveBooking(
  bookingId: string,
  expectedVersion: number,
): Promise<ApiResult<BookingPreview>> {
  return read(() =>
    apiClient.POST("/api/v1/bookings/{bookingId}/reserve", {
      params: { path: { bookingId } },
      body: { expectedVersion },
    }),
  );
}
export function cancelBooking(
  bookingId: string,
  expectedVersion: number,
): Promise<ApiResult<BookingRecord>> {
  return read(() =>
    apiClient.POST("/api/v1/bookings/{bookingId}/cancel", {
      params: { path: { bookingId } },
      body: { expectedVersion },
    }),
  );
}

export function getCheckoutManifest(bookingId: string): Promise<ApiResult<CheckoutManifest>> {
  return read(() =>
    apiClient.GET("/api/v1/bookings/{bookingId}/checkout-manifest", {
      params: { path: { bookingId } },
    }),
  );
}

export function checkoutBooking(
  bookingId: string,
  input: CheckoutInput,
): Promise<ApiResult<CheckoutManifest>> {
  return read(() =>
    apiClient.POST("/api/v1/bookings/{bookingId}/checkout", {
      params: { path: { bookingId } },
      body: input,
    }),
  );
}

export function checkInBookingAsset(
  bookingId: string,
  assetId: string,
  mutationId: string,
): Promise<ApiResult<CheckoutManifest>> {
  return read(() =>
    apiClient.POST("/api/v1/bookings/{bookingId}/check-in/assets/{assetId}", {
      params: { path: { bookingId, assetId } },
      body: { mutationId },
    }),
  );
}

export function returnBookingConsumable(
  bookingId: string,
  manifestConsumableId: string,
  input: components["schemas"]["ReturnBookingConsumableRequest"],
): Promise<ApiResult<CheckoutManifest>> {
  return read(() =>
    apiClient.POST("/api/v1/bookings/{bookingId}/check-in/consumables/{manifestConsumableId}", {
      params: { path: { bookingId, manifestConsumableId } },
      body: input,
    }),
  );
}

export function completeBookingReturn(
  bookingId: string,
  mutationId: string,
): Promise<ApiResult<CheckoutManifest>> {
  return read(() =>
    apiClient.POST("/api/v1/bookings/{bookingId}/check-in/complete", {
      params: { path: { bookingId } },
      body: { mutationId },
    }),
  );
}
