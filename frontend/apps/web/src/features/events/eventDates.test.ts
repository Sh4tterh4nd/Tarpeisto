import { describe, expect, it } from "vitest";
import {
  eventOverlapsDay,
  instantToLocalDateTime,
  localDateTimeToInstant,
  monthBounds,
} from "./eventDates";

describe("event date helpers", () => {
  it("turns local datetime input into the UTC instant required by the API", () => {
    const value = "2026-10-03T09:30";
    expect(localDateTimeToInstant(value)).toBe(new Date(value).toISOString());
    expect(instantToLocalDateTime(localDateTimeToInstant(value))).toBe(value);
  });

  it("uses a half-open month range and includes events spanning a day", () => {
    const bounds = monthBounds(new Date(2026, 9, 12));
    expect(bounds.from).toBe(new Date(2026, 9, 1).toISOString());
    expect(bounds.until).toBe(new Date(2026, 10, 1).toISOString());
    expect(
      eventOverlapsDay("2026-10-01T23:00:00Z", "2026-10-03T01:00:00Z", new Date(2026, 9, 2)),
    ).toBe(true);
    const day = new Date(2026, 9, 2);
    expect(
      eventOverlapsDay(new Date(day.getTime() - 3_600_000).toISOString(), day.toISOString(), day),
    ).toBe(false);
  });
});
