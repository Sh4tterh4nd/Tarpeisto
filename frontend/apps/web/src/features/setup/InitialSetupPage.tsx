import { useRef, useState, type FormEvent } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Container from "@mui/material/Container";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import type { AppError } from "@tarpeisto/api-client";
import { useSession } from "../identity/useSession";
import { completeInitialSetup } from "./setupApi";

function describeSetupError(error: AppError): string {
  if (error.kind === "network") {
    return "Could not reach the server. Check your connection and try again.";
  }
  if (error.errorCode === "SETUP_ALREADY_COMPLETED") {
    return "Setup was already completed in another browser. Continue to sign in.";
  }
  return error.problem?.detail ?? "The organization could not be created.";
}

/** One-time installation form replacing environment-driven Owner seeding. */
export function InitialSetupPage() {
  const { signIn, refresh } = useSession();
  const [organizationName, setOrganizationName] = useState("Default Organization");
  const [displayName, setDisplayName] = useState("Owner");
  const [email, setEmail] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | undefined>();
  const errorRef = useRef<HTMLDivElement>(null);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(undefined);
    if (password.length < 10) {
      setError("Use at least 10 characters for the Owner password.");
      queueMicrotask(() => errorRef.current?.focus());
      return;
    }
    if (password !== confirmPassword) {
      setError("The passwords do not match.");
      queueMicrotask(() => errorRef.current?.focus());
      return;
    }

    setSubmitting(true);
    const setupError = await completeInitialSetup({
      organizationName,
      displayName,
      email: email || undefined,
      username,
      password,
    });
    if (setupError) {
      setSubmitting(false);
      setError(describeSetupError(setupError));
      if (setupError.errorCode === "SETUP_ALREADY_COMPLETED") {
        await refresh();
      }
      queueMicrotask(() => errorRef.current?.focus());
      return;
    }

    const signInError = await signIn({ username, password });
    if (signInError) {
      await refresh();
    }
  }

  return (
    <Box
      component="main"
      sx={{
        minHeight: "100vh",
        display: "flex",
        alignItems: "center",
        bgcolor: "background.default",
        py: { xs: 3, md: 6 },
      }}
    >
      <Container maxWidth="lg">
        <Box
          sx={{
            display: "grid",
            gridTemplateColumns: { xs: "1fr", md: "minmax(280px, 0.82fr) minmax(420px, 1.18fr)" },
            alignItems: "stretch",
            border: 1,
            borderColor: "divider",
            borderRadius: 3,
            overflow: "hidden",
            bgcolor: "background.paper",
          }}
        >
          <Box
            sx={{
              bgcolor: "primary.main",
              color: "primary.contrastText",
              p: { xs: 3, sm: 5 },
              display: "flex",
              flexDirection: "column",
              justifyContent: "space-between",
              gap: 5,
            }}
          >
            <Box>
              <Typography variant="h3" component="div" sx={{ mb: 5 }}>
                Tarpeisto
              </Typography>
              <Typography variant="h1" component="h1" sx={{ maxWidth: 430, mb: 2 }}>
                Set up your inventory home
              </Typography>
              <Typography sx={{ maxWidth: 430, color: "rgba(255,255,255,0.82)" }}>
                Create the organization and the local Owner account that will manage equipment,
                containers, and users.
              </Typography>
            </Box>

            <Box
              aria-hidden="true"
              sx={{
                width: "min(100%, 320px)",
                border: "2px solid rgba(255,255,255,0.7)",
                borderRadius: 1.5,
                p: 2,
                transform: "rotate(-1.5deg)",
              }}
            >
              <Box sx={{ height: 8, bgcolor: "secondary.main", mb: 2 }} />
              <Box sx={{ height: 7, width: "72%", bgcolor: "rgba(255,255,255,0.82)", mb: 1.25 }} />
              <Box sx={{ height: 7, width: "48%", bgcolor: "rgba(255,255,255,0.44)", mb: 2.5 }} />
              <Box
                sx={{ display: "grid", gridTemplateColumns: "repeat(6, 1fr)", gap: 0.5, width: 70 }}
              >
                {[1, 0, 1, 1, 0, 1, 1, 1, 0, 0, 1, 0, 1, 0, 1, 1, 1, 1].map((filled, index) => (
                  <Box
                    key={index}
                    sx={{ aspectRatio: "1", bgcolor: filled ? "common.white" : "transparent" }}
                  />
                ))}
              </Box>
            </Box>
          </Box>

          <Paper elevation={0} square sx={{ p: { xs: 3, sm: 5, lg: 6 } }}>
            <Typography variant="h2" component="h2" sx={{ mb: 1 }}>
              Organization and Owner
            </Typography>
            <Typography color="text.secondary" sx={{ mb: 4, maxWidth: 620 }}>
              These details are stored in Tarpeisto. No bootstrap credentials or restart are needed.
            </Typography>

            <Stack component="form" spacing={2.5} onSubmit={handleSubmit} noValidate>
              <TextField
                label="Organization name"
                value={organizationName}
                onChange={(event) => setOrganizationName(event.target.value)}
                autoComplete="organization"
                required
                autoFocus
              />
              <Box
                sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr" }, gap: 2 }}
              >
                <TextField
                  label="Owner display name"
                  value={displayName}
                  onChange={(event) => setDisplayName(event.target.value)}
                  autoComplete="name"
                  required
                />
                <TextField
                  label="Owner email"
                  type="email"
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  autoComplete="email"
                  helperText="Optional"
                />
              </Box>
              <TextField
                label="Owner username"
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                autoComplete="username"
                required
              />
              <Box
                sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr" }, gap: 2 }}
              >
                <TextField
                  label="Password"
                  type="password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  autoComplete="new-password"
                  helperText="At least 10 characters"
                  required
                />
                <TextField
                  label="Confirm password"
                  type="password"
                  value={confirmPassword}
                  onChange={(event) => setConfirmPassword(event.target.value)}
                  autoComplete="new-password"
                  required
                />
              </Box>
              {error ? (
                <Alert severity="error" role="alert" tabIndex={-1} ref={errorRef}>
                  {error}
                </Alert>
              ) : null}
              <Button
                type="submit"
                variant="contained"
                size="large"
                disabled={
                  submitting ||
                  !organizationName.trim() ||
                  !displayName.trim() ||
                  !username.trim() ||
                  !password ||
                  !confirmPassword
                }
                sx={{ alignSelf: { sm: "flex-start" }, px: 4 }}
              >
                {submitting ? "Creating organization…" : "Create organization"}
              </Button>
            </Stack>
          </Paper>
        </Box>
      </Container>
    </Box>
  );
}
