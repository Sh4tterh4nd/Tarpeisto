import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PageHeading } from "./PageHeading";

describe("PageHeading", () => {
  it("renders the title as a heading and an optional description", () => {
    render(<PageHeading title="Categories" description="Organize equipment by category." />);

    expect(screen.getByRole("heading", { name: "Categories" })).toBeInTheDocument();
    expect(screen.getByText("Organize equipment by category.")).toBeInTheDocument();
  });

  it("renders provided actions", () => {
    render(
      <PageHeading title="Categories" actions={<button type="button">New category</button>} />,
    );

    expect(screen.getByRole("button", { name: "New category" })).toBeInTheDocument();
  });

  it("omits the description when none is given", () => {
    render(<PageHeading title="Categories" />);

    expect(screen.queryByText(/organize/i)).not.toBeInTheDocument();
  });
});
