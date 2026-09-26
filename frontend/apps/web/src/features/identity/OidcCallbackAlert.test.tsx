import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { OidcCallbackAlert } from "./OidcCallbackAlert";

function renderAt(initialEntry: string) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route path="/" element={<OidcCallbackAlert />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("OidcCallbackAlert", () => {
  it("renders nothing when there is no oidcError query parameter", () => {
    renderAt("/");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("renders a specific, actionable message for a known denial reason", async () => {
    renderAt("/?oidcError=AMBIGUOUS_EMAIL_MATCH");

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/more than one bigcontainers account matches/i);
  });

  it("falls back to a generic message for an unrecognized error code, never a stack trace", async () => {
    renderAt("/?oidcError=SOMETHING_UNEXPECTED");

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/single sign-on could not complete/i);
    expect(alert).toHaveTextContent(/local username\/password sign-in remains available/i);
  });
});
