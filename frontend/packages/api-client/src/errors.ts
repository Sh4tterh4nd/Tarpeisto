/**
 * The backend's RFC 9457 Problem Details body, extended with the `errorCode`
 * application extension (spec section 28, ADR-0001).
 *
 * This type cannot be generated: the live `/v3/api-docs` document captured
 * from the running backend (see `openapi/backend-openapi.json`) only
 * documents the success response for every operation and never references a
 * `ProblemDetails`/error schema, so `openapi-typescript` never emits one.
 * That is a gap in the backend's springdoc annotations, not a frontend
 * choice, and it is out of scope for this package (frontend-owned) to fix.
 * RFC 9457 itself is a stable external standard, not a BigContainers
 * request/response shape, so hand-maintaining its shape here — with the
 * `errorCode` extension documented in the specification — does not
 * duplicate generated code the way a hand-written `UserResponse` or
 * `SessionResponse` would.
 */
export interface ProblemDetails {
  type: string;
  title: string;
  status: number;
  detail?: string;
  instance?: string;
  /** Stable machine-readable application error code extension. */
  errorCode?: string;
}

export type AppErrorKind = "problem" | "unauthenticated" | "network" | "unknown";

/**
 * A typed, UI-friendly representation of anything that can go wrong calling
 * the API: a well-formed Problem Details response, an authentication
 * failure, a network/transport failure, or an unrecognized failure shape.
 * Feature code should branch on `kind` rather than inspecting HTTP status
 * codes directly.
 */
export class AppError extends Error {
  readonly kind: AppErrorKind;
  readonly status: number | undefined;
  readonly errorCode: string | undefined;
  readonly problem: ProblemDetails | undefined;

  private constructor(
    kind: AppErrorKind,
    message: string,
    options: { status?: number; errorCode?: string; problem?: ProblemDetails } = {},
  ) {
    super(message);
    this.name = "AppError";
    this.kind = kind;
    this.status = options.status;
    this.errorCode = options.errorCode;
    this.problem = options.problem;
  }

  static fromProblemDetails(problem: ProblemDetails, status: number): AppError {
    if (status === 401) {
      return new AppError("unauthenticated", problem.title ?? "Authentication required", {
        status,
        errorCode: problem.errorCode,
        problem,
      });
    }
    return new AppError("problem", problem.title ?? "Request failed", {
      status,
      errorCode: problem.errorCode,
      problem,
    });
  }

  static network(cause: unknown): AppError {
    const message = cause instanceof Error ? cause.message : "Network request failed";
    return new AppError("network", message);
  }

  static unknown(status: number): AppError {
    return new AppError("unknown", `Request failed with status ${status}`, { status });
  }
}
