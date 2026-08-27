/** 与后端 ScriptTemplateValidator 对齐的前端即时校验。 */

export const ALLOWED_VARS = ["name", "amount", "dpd", "repaymentUrl"] as const;

export const LIMITS = {
  smsBodyMax: 300,
  smsRenderedWarn: 160,
  smsRenderedSoft: 320,
  smsRenderedMax: 400,
  pushTitleMax: 40,
  pushBodyMax: 120,
  pushTitleRenderedMax: 60,
  pushBodyRenderedMax: 180
} as const;

const SAMPLE = {
  name: "Juan Dela Cruz",
  amount: "999,999.99",
  dpd: "999",
  repaymentUrl: "https://mocasa.com/s/4cTu"
};

const PLACEHOLDER_RE = /\{([a-zA-Z][a-zA-Z0-9_]*)\}/g;

export type Issue = {
  field: string;
  code: string;
  message: string;
  severity: "ERROR" | "WARN";
};

export type LocalValidation = {
  valid: boolean;
  errors: Issue[];
  warnings: Issue[];
  preview: {
    titleRendered?: string;
    bodyRendered?: string;
    titleLength?: number;
    bodyLength?: number;
    titleRenderedLength?: number;
    bodyRenderedLength?: number;
  };
};

export function injectSample(template: string): string {
  let out = template
    .split("{name}")
    .join(SAMPLE.name)
    .split("{amount}")
    .join(SAMPLE.amount)
    .split("{dpd}")
    .join(SAMPLE.dpd)
    .split("{repaymentUrl}")
    .join(SAMPLE.repaymentUrl);
  out = out.replace(": ,", ": ");
  out = out.replace(/\s{2,}/g, " ");
  out = out.replace(/^[,\s]+/, "");
  return out.trim();
}

function extractVars(text: string): string[] {
  const found: string[] = [];
  const seen = new Set<string>();
  let m: RegExpExecArray | null;
  const re = new RegExp(PLACEHOLDER_RE.source, "g");
  while ((m = re.exec(text)) !== null) {
    if (!seen.has(m[1])) {
      seen.add(m[1]);
      found.push(m[1]);
    }
  }
  return found;
}

function validateField(
  field: string,
  text: string | undefined,
  templateMax: number,
  renderedMax: number,
  renderedSoft: number,
  renderedWarn: number,
  required: boolean,
  requiredVars: string[],
  errors: Issue[],
  warnings: Issue[],
  preview: LocalValidation["preview"]
) {
  if (!text || !text.trim()) {
    if (required) {
      errors.push({
        field,
        code: "REQUIRED",
        message: `${field} is required`,
        severity: "ERROR"
      });
    }
    return;
  }
  const found = extractVars(text);
  for (const v of found) {
    if (!(ALLOWED_VARS as readonly string[]).includes(v)) {
      errors.push({
        field,
        code: "UNKNOWN_VAR",
        message: `Unknown placeholder: {${v}}. Allowed: ${ALLOWED_VARS.join(", ")}`,
        severity: "ERROR"
      });
    }
  }
  for (const need of requiredVars) {
    if (!found.includes(need)) {
      errors.push({
        field,
        code: "MISSING_VAR",
        message: `Missing required placeholder: {${need}}`,
        severity: "ERROR"
      });
    }
  }
  if (text.length > templateMax) {
    errors.push({
      field,
      code: "MAX_LENGTH",
      message: `${field} exceeds ${templateMax} characters (current: ${text.length})`,
      severity: "ERROR"
    });
  }
  const rendered = injectSample(text);
  if (field === "title") {
    preview.titleRendered = rendered;
    preview.titleLength = text.length;
    preview.titleRenderedLength = rendered.length;
  } else {
    preview.bodyRendered = rendered;
    preview.bodyLength = text.length;
    preview.bodyRenderedLength = rendered.length;
  }
  if (rendered.length > renderedMax) {
    errors.push({
      field,
      code: "RENDERED_MAX_LENGTH",
      message: `${field} rendered length exceeds ${renderedMax} (sample: ${rendered.length})`,
      severity: "ERROR"
    });
  } else if (rendered.length > renderedSoft) {
    warnings.push({
      field,
      code: "RENDERED_SOFT_LENGTH",
      message: `${field} rendered length is ${rendered.length} (> ${renderedSoft}); may cost multiple SMS segments`,
      severity: "WARN"
    });
  } else if (rendered.length > renderedWarn) {
    warnings.push({
      field,
      code: "SEGMENT_WARN",
      message: `${field} rendered length is ${rendered.length} (> ${renderedWarn} GSM segment); expect multi-segment cost`,
      severity: "WARN"
    });
  }
}

export function validateScriptTemplateLocal(
  channel: string,
  title: string,
  body: string
): LocalValidation {
  const errors: Issue[] = [];
  const warnings: Issue[] = [];
  const preview: LocalValidation["preview"] = {};
  const ch = (channel || "").toUpperCase();

  if (ch === "SMS") {
    validateField(
      "body",
      body,
      LIMITS.smsBodyMax,
      LIMITS.smsRenderedMax,
      LIMITS.smsRenderedSoft,
      LIMITS.smsRenderedWarn,
      true,
      ["amount", "repaymentUrl"],
      errors,
      warnings,
      preview
    );
  } else if (ch === "PUSH") {
    if (!title.trim() && !body.trim()) {
      errors.push({
        field: "body",
        code: "REQUIRED",
        message: "Push title and body cannot both be empty",
        severity: "ERROR"
      });
    }
    validateField(
      "title",
      title,
      LIMITS.pushTitleMax,
      LIMITS.pushTitleRenderedMax,
      LIMITS.pushTitleRenderedMax,
      LIMITS.pushTitleMax,
      false,
      [],
      errors,
      warnings,
      preview
    );
    validateField(
      "body",
      body,
      LIMITS.pushBodyMax,
      LIMITS.pushBodyRenderedMax,
      LIMITS.pushBodyRenderedMax,
      LIMITS.pushBodyMax,
      false,
      [],
      errors,
      warnings,
      preview
    );
  }

  return { valid: errors.length === 0, errors, warnings, preview };
}

export function insertPlaceholder(text: string, cursor: number, varName: string): string {
  const token = `{${varName}}`;
  const start = Math.max(0, Math.min(cursor, text.length));
  return text.slice(0, start) + token + text.slice(start);
}
