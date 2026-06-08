import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

const PUBLIC_PATHS = ["/login", "/register", "/landing", "/demo"];

export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const token = request.cookies.get("edumerge_token")?.value;

  // 未登录且访问受保护页面 → 跳转介绍页
  if (!token && !PUBLIC_PATHS.some((p) => pathname.startsWith(p))) {
    const landingUrl = new URL("/landing", request.url);
    return NextResponse.redirect(landingUrl);
  }

  // 已登录访问登录/注册页 → 跳转首页
  if (token && (pathname === "/login" || pathname === "/register")) {
    return NextResponse.redirect(new URL("/", request.url));
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico|logo_converted.svg).*)"],
};
