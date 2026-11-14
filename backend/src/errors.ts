import { z } from 'zod';

export class ApiError extends Error {
  constructor(readonly statusCode: number, readonly code: string, message: string, readonly details?: unknown) {
    super(message);
  }
}

export function parse<T>(schema: z.ZodType<T>, value: unknown): T {
  const result = schema.safeParse(value);
  if (result.success) return result.data;
  throw new ApiError(400, 'INVALID_INPUT', 'Revisa los datos enviados', result.error.issues.map(issue => ({
    field: issue.path.join('.'),
    rule: issue.code,
  })));
}
