/** Converts a native `datetime-local` control value to the instant the API stores. */
export function localDateTimeToInstant(value: string): string {
  return new Date(value).toISOString();
}

/** Produces the local form control representation without silently changing timezone. */
export function instantToLocalDateTime(value: string): string {
  const date = new Date(value);
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}

export function monthBounds(month: Date): { from: string; until: string } {
  const from = new Date(month.getFullYear(), month.getMonth(), 1);
  const until = new Date(month.getFullYear(), month.getMonth() + 1, 1);
  return { from: from.toISOString(), until: until.toISOString() };
}

export function eventOverlapsDay(startsAt: string, endsAt: string, day: Date): boolean {
  const start = new Date(startsAt).getTime();
  const end = new Date(endsAt).getTime();
  const dayStart = new Date(day.getFullYear(), day.getMonth(), day.getDate()).getTime();
  const dayEnd = dayStart + 86_400_000;
  return start < dayEnd && end > dayStart;
}
