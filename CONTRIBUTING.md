# Contributing to DataTurntable

Thank you for your interest in contributing to DataTurntable!

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Environment](#development-environment)
- [Making Changes](#making-changes)
- [Testing](#testing)
- [Commit Guidelines](#commit-guidelines)
- [Pull Request Process](#pull-request-process)

## Code of Conduct

This project and everyone participating in it is governed by our [Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.

## Getting Started

1. Fork the repository
2. Clone your fork: `git clone https://github.com/YOUR_USERNAME/dataturntable.git`
3. Create a feature branch: `git checkout -b feature/your-feature-name`

## Development Environment

### Prerequisites

- Docker & Docker Compose
- Java 21+
- Maven 3.9+
- Node.js 18+
- npm 9+

### Local Development

1. Start infrastructure:
```bash
cd docker
docker-compose -f docker-compose.dev.yml up -d
```

2. Run backend:
```bash
cd backend
mvn quarkus:dev
```

3. Run frontend:
```bash
cd frontend
npm install
npm run dev
```

## Making Changes

### Backend (Quarkus/Kotlin)

- Follow Kotlin coding conventions
- Use meaningful variable names
- Add KDoc comments for public APIs
- Write unit tests for new features

### Frontend (React/TypeScript)

- Follow ESLint configuration
- Use TypeScript for all new code
- Write component tests
- Use functional components with hooks

### File Organization

```
backend/
├── src/main/kotlin/com/dataturntable/
│   ├── api/          # REST endpoints
│   ├── service/      # Business logic
│   ├── entity/       # Database entities
│   └── dto/         # Data transfer objects

frontend/
├── src/
│   ├── components/   # Reusable UI components
│   ├── pages/       # Page components
│   ├── services/    # API services
│   ├── stores/      # State management
│   └── __tests__/   # Test files
```

## Testing

<!-- TODO: Add a CI workflow for the documented checks
     Details: The repository contains no GitHub Actions workflow, so Maven compilation/tests, frontend type-check/build/tests, and manifest rendering are not automatically enforced for pull requests. Add a reproducible CI matrix and make its required checks visible here. -->
### Backend Tests

Run unit tests:
```bash
cd backend
mvn test
```

Run integration tests:
```bash
cd backend
mvn verify
```

### Frontend Tests

Run tests:
```bash
cd frontend
npm test
```

Run with coverage:
```bash
cd frontend
npm run test:coverage
```

## Commit Guidelines

We follow [Conventional Commits](https://conventionalcommits.org/) for commit messages:

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

Types:
- `feat`: A new feature
- `fix`: A bug fix
- `docs`: Documentation only changes
- `style`: Changes that don't affect code meaning (formatting)
- `refactor`: Code change that neither fixes a bug nor adds a feature
- `test`: Adding or updating tests
- `chore`: Changes to build process or auxiliary tools

Examples:
```
feat(auth): add JWT token refresh
fix(module): handle null version tag
docs(api): update endpoint documentation
test(pipeline): add integration tests for pipeline execution
```

## Pull Request Process

1. Ensure all tests pass locally
2. Update documentation for any changes
3. Add a clear description of the changes
4. Link any related issues
5. Request review from maintainers

### PR Title Format

Use the same format as commit messages:
```
feat(auth): add user registration endpoint
```

## Questions?

Feel free to open an issue for questions about contributing.
