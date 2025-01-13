## CI/CD Pipeline Diagram

```mermaid
graph TD
    A[Checkout Code] --> B{Static Analysis}
    B -->|SonarQube Analysis| C[Run SonarQube Scanner]
    B -->|Security Scan| D[OWASP Dependency Check]
    C --> E[Quality Gate Check]
    D --> E
    E --> F{Build & Test}
    F --> G[Run Unit Tests]
    G --> H[Build Docker Image]
    F --> H
    H --> I[Integration Tests]
    I --> J{Push to Registry}
    J -->|Branch = main| K[Deploy to Staging]
    K --> L[Manual Approval for Production]
    L -->|Approved| M[Deploy to Production]
    M --> N[Notify Slack]
