import { AppError, apiClient, toAppError } from "@tarpeisto/api-client";
import type { components } from "@tarpeisto/api-client";

export type InitialSetupInput = components["schemas"]["CompleteInitialSetupRequest"];
export type InitialSetupStatus = Required<components["schemas"]["InitialSetupStatusResponse"]>;

export async function fetchInitialSetupStatus(): Promise<InitialSetupStatus | undefined> {
  try {
    const { data, error, response } = await apiClient.GET("/api/v1/setup");
    if (error || !response.ok || data?.setupRequired === undefined) {
      return undefined;
    }
    return data as InitialSetupStatus;
  } catch {
    return undefined;
  }
}

export async function completeInitialSetup(
  input: InitialSetupInput,
): Promise<AppError | undefined> {
  try {
    const { error, response } = await apiClient.POST("/api/v1/setup", { body: input });
    if (!response.ok) {
      return toAppError(error, response.status);
    }
    return undefined;
  } catch (cause) {
    return AppError.network(cause);
  }
}
