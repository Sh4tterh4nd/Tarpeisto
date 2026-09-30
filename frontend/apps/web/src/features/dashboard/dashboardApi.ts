import { apiClient, AppError, toAppError, type components } from "@tarpeisto/api-client";

export type DashboardRow = Required<components["schemas"]["DashboardRowResponse"]>;
export type DashboardQueue = NonNullable<components["schemas"]["DashboardQueueResponse"]["queue"]>;
export type DashboardPage = Omit<
  Required<components["schemas"]["DashboardQueueResponse"]>,
  "items" | "nextCursor"
> & { items: DashboardRow[]; nextCursor?: string };
export type Dashboard = Omit<Required<components["schemas"]["DashboardResponse"]>, "queues"> & {
  queues: DashboardPage[];
};
type Result<T> = { kind: "ok"; data: T } | { kind: "error"; error: AppError };

export async function getDashboard(): Promise<Result<Dashboard>> {
  try {
    const { data, error, response } = await apiClient.GET("/api/v1/dashboard");
    return response.ok && data
      ? { kind: "ok", data: data as Dashboard }
      : { kind: "error", error: toAppError(error, response.status) };
  } catch (cause) {
    return { kind: "error", error: AppError.network(cause) };
  }
}
export async function getDashboardPage(
  queue: DashboardQueue,
  cursor: string,
): Promise<Result<DashboardPage>> {
  try {
    const { data, error, response } = await apiClient.GET("/api/v1/dashboard/{queue}", {
      params: { path: { queue }, query: { cursor, limit: 25 } },
    });
    return response.ok && data
      ? { kind: "ok", data: data as DashboardPage }
      : { kind: "error", error: toAppError(error, response.status) };
  } catch (cause) {
    return { kind: "error", error: AppError.network(cause) };
  }
}
