import type { ReactNode } from "react";
import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

export interface PageHeadingProps {
  title: string;
  description?: string;
  /** Actions such as buttons, rendered aligned to the end of the heading row. */
  actions?: ReactNode;
}

/**
 * BigContainers-owned page-heading wrapper: a consistent `<h2>` title, optional
 * supporting description, and an optional action area, used at the top of a
 * page's main content.
 */
export function PageHeading({ title, description, actions }: PageHeadingProps) {
  return (
    <Stack
      direction={{ xs: "column", sm: "row" }}
      spacing={1}
      sx={{
        justifyContent: "space-between",
        alignItems: { xs: "flex-start", sm: "center" },
        mb: 3,
      }}
    >
      <Box>
        <Typography variant="h2" component="h2">
          {title}
        </Typography>
        {description ? (
          <Typography variant="body1" color="text.secondary">
            {description}
          </Typography>
        ) : null}
      </Box>
      {actions ? <Box>{actions}</Box> : null}
    </Stack>
  );
}
