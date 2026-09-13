FROM node:22-bookworm-slim AS build
ARG RCM_CI_BUILD=false
WORKDIR /src
RUN test "$RCM_CI_BUILD" = "true" || (echo "React production Docker builds are GitHub Actions-only" >&2 && exit 2)
ENV GITHUB_ACTIONS=${RCM_CI_BUILD}
RUN corepack enable && corepack prepare pnpm@10.15.0 --activate
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

FROM nginx:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080
