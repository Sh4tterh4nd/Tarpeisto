import { apiClient, AppError, toAppError, type components } from "@tarpeisto/api-client";

const paths = {
  stock: "/api/v1/consumable-stock/{id}/archive",
  user: "/api/v1/users/{id}/archive",
  booking: "/api/v1/bookings/{id}/archive",
  audit: "/api/v1/audits/{id}/archive",
} as const;
export type ArchiveKind = keyof typeof paths;
export async function changeArchive(
  kind: ArchiveKind,
  id: string,
  archived: boolean,
  expectedVersion: number,
): Promise<AppError | undefined> {
  try {
    const body: components["schemas"]["ChangeArchiveRequest"] = { archived, expectedVersion };
    const { error, response } = await apiClient.PUT(paths[kind], {
      params: { path: { id } },
      body,
    });
    return response.ok ? undefined : toAppError(error, response.status);
  } catch (cause) {
    return AppError.network(cause);
  }
}
