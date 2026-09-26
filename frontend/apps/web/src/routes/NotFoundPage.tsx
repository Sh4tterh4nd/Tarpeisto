import Typography from "@mui/material/Typography";
import { PageHeading } from "@bigcontainers/shared-ui";

export function NotFoundPage() {
  return (
    <>
      <PageHeading title="Page not found" />
      <Typography>There is nothing at this address.</Typography>
    </>
  );
}
