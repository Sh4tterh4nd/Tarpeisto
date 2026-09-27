import { describe, expect, it } from "vitest";
import { AppError } from "./errors";
import type { ProblemDetails } from "./errors";

describe("AppError.fromProblemDetails", () => {
  it("maps a 401 response to an unauthenticated error", () => {
    const problem: ProblemDetails = {
      type: "about:blank",
      title: "Unauthorized",
      status: 401,
      errorCode: "AUTH_REQUIRED",
    };

    const error = AppError.fromProblemDetails(problem, 401);

    expect(error.kind).toBe("unauthenticated");
    expect(error.status).toBe(401);
    expect(error.errorCode).toBe("AUTH_REQUIRED");
    expect(error.problem).toBe(problem);
  });

  it("maps any other status to a generic problem error", () => {
    const problem: ProblemDetails = {
      type: "https://tarpeisto.example/errors/validation",
      title: "Validation failed",
      status: 422,
      detail: "Name is required",
      errorCode: "VALIDATION_FAILED",
    };

    const error = AppError.fromProblemDetails(problem, 422);

    expect(error.kind).toBe("problem");
    expect(error.status).toBe(422);
    expect(error.message).toBe("Validation failed");
    expect(error.errorCode).toBe("VALIDATION_FAILED");
  });
});

describe("AppError.network", () => {
  it("wraps a thrown Error's message", () => {
    const error = AppError.network(new TypeError("Failed to fetch"));

    expect(error.kind).toBe("network");
    expect(error.message).toBe("Failed to fetch");
  });

  it("falls back to a generic message for a non-Error cause", () => {
    const error = AppError.network("boom");

    expect(error.kind).toBe("network");
    expect(error.message).toBe("Network request failed");
  });
});

describe("AppError.unknown", () => {
  it("carries the status with a generic message", () => {
    const error = AppError.unknown(500);

    expect(error.kind).toBe("unknown");
    expect(error.status).toBe(500);
  });
});
