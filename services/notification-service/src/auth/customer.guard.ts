import { CanActivate, ExecutionContext, Injectable, UnauthorizedException } from "@nestjs/common";

import { bearerOrCookie, TokenVerifier } from "./token-verifier";

export type CustomerRequest = {
  headers: { authorization?: string; cookie?: string };
  customerId?: string;
};

@Injectable()
export class CustomerGuard implements CanActivate {
  constructor(private readonly tokens: TokenVerifier) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest<CustomerRequest>();
    const subject = await this.tokens.subject(bearerOrCookie(request.headers.authorization, request.headers.cookie, undefined));
    if (!subject) {
      throw new UnauthorizedException();
    }
    request.customerId = subject;
    return true;
  }
}
