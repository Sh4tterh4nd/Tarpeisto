import { CSRF_HEADER_NAME, readCsrfCookie, type components } from "@bigcontainers/api-client";

export type MediaRecord = Required<components["schemas"]["MediaResponse"]> & {
  primaryImage?: boolean;
};

async function request(path: string, init?: RequestInit): Promise<Response> {
  const headers = new Headers(init?.headers);
  if (init?.method && init.method !== "GET") {
    const token = readCsrfCookie();
    if (token) headers.set(CSRF_HEADER_NAME, token);
  }
  return fetch(path, { credentials: "include", ...init, headers });
}

async function responseError(response: Response): Promise<Error> {
  const problem = (await response.json().catch(() => undefined)) as { detail?: string } | undefined;
  return new Error(problem?.detail ?? "The image request failed.");
}

export async function getMedia(path: string): Promise<MediaRecord | undefined> {
  const response = await request(path);
  if (response.status === 404) return undefined;
  if (!response.ok) throw await responseError(response);
  return (await response.json()) as MediaRecord;
}

export async function listMedia(path: string): Promise<MediaRecord[]> {
  const response = await request(path);
  if (!response.ok) throw await responseError(response);
  return (await response.json()) as MediaRecord[];
}

export async function uploadMedia(
  path: string,
  file: File,
  caption?: string,
): Promise<MediaRecord> {
  const body = new FormData();
  body.append("file", file);
  if (caption?.trim()) body.append("caption", caption.trim());
  const response = await request(path, { method: "POST", body });
  if (!response.ok) throw await responseError(response);
  return (await response.json()) as MediaRecord;
}

export async function updateLayoutMedia(
  mediaId: string,
  caption: string,
  displayOrder: number,
  primaryImage: boolean,
  version: number,
): Promise<MediaRecord[]> {
  const response = await request(`/api/v1/media/${mediaId}/layout`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      caption: caption.trim() || undefined,
      displayOrder,
      primaryImage,
      version,
    }),
  });
  if (!response.ok) throw await responseError(response);
  return (await response.json()) as MediaRecord[];
}

export async function deleteMedia(mediaId: string): Promise<void> {
  const response = await request(`/api/v1/media/${mediaId}`, { method: "DELETE" });
  if (!response.ok) throw await responseError(response);
}
