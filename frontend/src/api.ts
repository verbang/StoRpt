import type { ApiError } from "./types";

export class ApiFailure extends Error {
  readonly detail: ApiError;
  readonly status: number;

  constructor(detail: ApiError, status: number) {
    super(detail.message);
    this.detail = detail;
    this.status = status;
  }
}

export async function apiFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const response = await fetch(input, {
    ...init,
    credentials: "same-origin",
    cache: "no-store"
  });
  if (response.ok) {
    return response;
  }
  let detail: ApiError = {
    code: "SYSTEM-004",
    category: "SYSTEM",
    stage: "system",
    title: "请求失败",
    message: "服务暂时不可用。"
  };
  try {
    const payload = await response.json();
    if (payload?.errors?.[0]) {
      detail = payload.errors[0] as ApiError;
    }
  } catch {
    // Keep the stable generic error when a proxy returns a non-JSON page.
  }
  throw new ApiFailure(detail, response.status);
}
