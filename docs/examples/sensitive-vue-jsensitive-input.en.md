# Vue `JSensitiveInput` Example

This example is adapted from the `crm-vue` `JSensitiveInput.vue`, `utils/sensitive/transform.ts`, and JEECG `BasicForm` integration. It is written as a minimal copyable implementation for frontend projects.

Use it with:

- Vue 3
- ant-design-vue
- axios
- backend save endpoints annotated with `@SensitiveRequestHydration`
- response DTOs that return masked fields plus `sensitiveLookupMeta`

## 1. Submit Contract

Prefer `meta` mode. When the user does not change a sensitive field, do not submit the masked field value. Submit `sensitiveSubmitMeta[field]` instead:

```json
{
  "id": "U-100",
  "name": "Alice",
  "sensitiveSubmitMeta": {
    "phone": {
      "sid": "user_account",
      "pid": "phone",
      "vid": "U-100",
      "hash": "7f8c...",
      "state": "unchangedMasked"
    }
  }
}
```

When the user changes the sensitive field, submit the new plaintext string:

```json
{
  "id": "U-100",
  "name": "Alice",
  "phone": "13900139000"
}
```

The backend only processes this contract when the endpoint explicitly opts in:

```java
@SensitiveRequestHydration
@PostMapping("/users")
public void save(@RequestBody UserSaveRequest request) {
    userService.save(request);
}
```

This is an internal conversion. It does not return plaintext to the caller and does not trigger plaintext-view auditing.

## 2. `src/utils/sensitive/transform.ts`

This file does not depend on JEECG types. If your project already has a `FormSchema` type, it only needs at least `field` and `component`.

```ts
export type SensitiveFieldState = 'masked' | 'revealed' | 'changed';
export type SensitiveSubmitMode = 'omit' | 'meta';

export interface SensitiveLookupMeta {
  sid: string;
  pid: string;
  vid: string;
  hash: string;
}

export interface SensitiveInputValue {
  value: string;
  maskedValue: string;
  lookupMeta: SensitiveLookupMeta;
  state: SensitiveFieldState;
}

export interface SensitiveSubmitMeta extends SensitiveLookupMeta {
  state: 'unchangedMasked';
}

export interface SensitiveFormSchema {
  field: string;
  component: string;
}

export type Recordable = Record<string, any>;

const SENSITIVE_LOOKUP_META_KEY = 'sensitiveLookupMeta';
const SENSITIVE_SUBMIT_META_KEY = 'sensitiveSubmitMeta';

export function isSensitiveInputValue(value: unknown): value is SensitiveInputValue {
  return !!value && typeof value === 'object' && !Array.isArray(value) && !!(value as SensitiveInputValue).lookupMeta;
}

export function isSensitiveFormSchema(schema?: SensitiveFormSchema | null): schema is SensitiveFormSchema {
  return schema?.component === 'JSensitiveInput';
}

export function getSensitiveFields(schemas: SensitiveFormSchema[]): string[] {
  return schemas.filter(isSensitiveFormSchema).map((schema) => schema.field).filter(Boolean);
}

export function beginSensitiveEdit(value: SensitiveInputValue): SensitiveInputValue {
  if (value.state !== 'masked') {
    return value;
  }
  return {
    ...value,
    value: '',
    state: 'changed',
  };
}

export function hydrateSensitiveFormValues(values: Recordable, schemas: SensitiveFormSchema[]): Recordable {
  if (!values || typeof values !== 'object') {
    return values;
  }
  const result = { ...values };
  const lookupMetaMap = result[SENSITIVE_LOOKUP_META_KEY];
  if (!lookupMetaMap || typeof lookupMetaMap !== 'object') {
    return result;
  }
  getSensitiveFields(schemas).forEach((field) => {
    const lookupMeta = lookupMetaMap[field];
    if (!isLookupMeta(lookupMeta)) {
      return;
    }
    const maskedValue = result[field] ?? '';
    result[field] = {
      value: String(maskedValue),
      maskedValue: String(maskedValue),
      lookupMeta,
      state: 'masked',
    } as SensitiveInputValue;
  });
  return result;
}

export function preprocessSensitiveSubmitValues(
  values: Recordable,
  schemas: SensitiveFormSchema[],
  mode: SensitiveSubmitMode = 'meta',
): Recordable {
  if (!values || typeof values !== 'object') {
    return values;
  }
  const result = { ...values };
  const submitMeta: Record<string, SensitiveSubmitMeta> = {};

  getSensitiveFields(schemas).forEach((field) => {
    const current = result[field];
    if (!isSensitiveInputValue(current)) {
      return;
    }
    if (current.state === 'changed') {
      result[field] = current.value;
      return;
    }
    delete result[field];
    if (mode === 'meta') {
      submitMeta[field] = {
        ...current.lookupMeta,
        state: 'unchangedMasked',
      };
    }
  });

  delete result[SENSITIVE_LOOKUP_META_KEY];
  delete result[SENSITIVE_SUBMIT_META_KEY];
  if (Object.keys(submitMeta).length > 0) {
    result[SENSITIVE_SUBMIT_META_KEY] = submitMeta;
  }
  return result;
}

function isLookupMeta(value: unknown): value is SensitiveLookupMeta {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return false;
  }
  const meta = value as SensitiveLookupMeta;
  return ['sid', 'pid', 'vid', 'hash'].every((key) => typeof meta[key] === 'string' && meta[key] !== '');
}
```

## 3. `src/components/sensitive/JSensitiveInput.vue`

This component keeps the core `crm-vue` behavior:

- initial display state is `masked`
- clicking the eye icon calls an explicit plaintext-view API
- focusing a masked field enters `changed` state and clears the old masked text
- `revealed` is only a frontend display state; saving still treats it as unchanged and submits `sensitiveSubmitMeta`
- clicking the eye button must stop default focus, otherwise the input `focus` handler runs first and incorrectly turns the masked value into edit mode

```vue
<template>
  <a-input v-bind="inputAttrs" v-model:value="inputValue" @focus="onFocus">
    <template #suffix>
      <a-tooltip v-if="showLookupButton" title="View plaintext">
        <span class="sensitive-eye" @mousedown.prevent.stop @click.stop="onToggleEye">
          <EyeInvisibleOutlined v-if="isRevealed" />
          <EyeOutlined v-else />
        </span>
      </a-tooltip>
      <slot name="suffix" />
    </template>
  </a-input>
</template>

<script lang="ts">
import { computed, defineComponent, ref, watch } from 'vue';
import axios from 'axios';
import { EyeInvisibleOutlined, EyeOutlined } from '@ant-design/icons-vue';
import {
  beginSensitiveEdit,
  isSensitiveInputValue,
  type SensitiveInputValue,
  type SensitiveLookupMeta,
} from '@/utils/sensitive/transform';

export default defineComponent({
  name: 'JSensitiveInput',
  components: {
    EyeInvisibleOutlined,
    EyeOutlined,
  },
  inheritAttrs: false,
  props: {
    value: {
      type: [String, Object],
      default: '',
    },
    placeholder: {
      type: String,
      default: '',
    },
    trim: {
      type: Boolean,
      default: false,
    },
    lookupUrl: {
      type: String,
      default: '/sensitive/plaintext/lookup',
    },
  },
  emits: ['change', 'update:value'],
  setup(props, { attrs, emit }) {
    const plainTextCache = ref('');
    const currentValue = ref<string | SensitiveInputValue>('');

    const inputAttrs = computed(() => ({
      ...attrs,
      placeholder: props.placeholder,
    }));

    watch(
      () => props.value,
      (value) => {
        currentValue.value = normalizeValue(value);
        if (isSensitiveInputValue(currentValue.value) && currentValue.value.state === 'revealed') {
          plainTextCache.value = currentValue.value.value;
        } else if (typeof value === 'string') {
          plainTextCache.value = '';
        }
      },
      { immediate: true, deep: true },
    );

    const inputValue = computed<string>({
      get() {
        return isSensitiveInputValue(currentValue.value)
          ? currentValue.value.value ?? currentValue.value.maskedValue ?? ''
          : String(currentValue.value ?? '');
      },
      set(value) {
        const text = props.trim ? value.trim() : value;
        updateValue(text);
      },
    });

    const showLookupButton = computed(
      () => isSensitiveInputValue(currentValue.value) && !!currentValue.value.lookupMeta && currentValue.value.state !== 'changed',
    );
    const isRevealed = computed(() => isSensitiveInputValue(currentValue.value) && currentValue.value.state === 'revealed');

    async function onToggleEye() {
      if (!isSensitiveInputValue(currentValue.value) || !currentValue.value.lookupMeta) {
        return;
      }
      if (currentValue.value.state === 'revealed') {
        const plainText = currentValue.value.value;
        currentValue.value = {
          ...currentValue.value,
          value: currentValue.value.maskedValue,
          state: 'masked',
        };
        plainTextCache.value = plainTextCache.value || plainText;
        emitCurrentValue();
        return;
      }
      if (plainTextCache.value) {
        currentValue.value = {
          ...currentValue.value,
          value: plainTextCache.value,
          state: 'revealed',
        };
        emitCurrentValue();
        return;
      }
      await fetchPlainText();
    }

    function onFocus() {
      if (!isSensitiveInputValue(currentValue.value) || currentValue.value.state !== 'masked') {
        return;
      }
      currentValue.value = beginSensitiveEdit(currentValue.value);
      emitCurrentValue();
    }

    async function fetchPlainText() {
      if (!isSensitiveInputValue(currentValue.value) || !currentValue.value.lookupMeta) {
        return;
      }
      const response = await axios.post<string>(props.lookupUrl, currentValue.value.lookupMeta as SensitiveLookupMeta);
      const text = typeof response.data === 'string' ? response.data : '';
      if (!text) {
        return;
      }
      plainTextCache.value = text;
      currentValue.value = {
        ...currentValue.value,
        value: text,
        state: 'revealed',
      };
      emitCurrentValue();
    }

    function updateValue(value: string) {
      if (isSensitiveInputValue(currentValue.value)) {
        currentValue.value = {
          ...currentValue.value,
          value,
          state: 'changed',
        };
        emitCurrentValue();
        return;
      }
      currentValue.value = value;
      emit('change', value);
      emit('update:value', value);
    }

    function emitCurrentValue() {
      emit('change', currentValue.value);
      emit('update:value', currentValue.value);
    }

    function normalizeValue(value: unknown): string | SensitiveInputValue {
      if (isSensitiveInputValue(value)) {
        return {
          value: value.state === 'masked' ? value.maskedValue ?? value.value ?? '' : value.value ?? value.maskedValue ?? '',
          maskedValue: value.maskedValue ?? value.value ?? '',
          lookupMeta: value.lookupMeta,
          state: value.state,
        };
      }
      if (typeof value === 'string') {
        return value;
      }
      return '';
    }

    return {
      inputAttrs,
      inputValue,
      onFocus,
      onToggleEye,
      showLookupButton,
      isRevealed,
    };
  },
});
</script>

<style scoped>
.sensitive-eye {
  cursor: pointer;
  display: inline-flex;
  align-items: center;
}
</style>
```

## 4. Plain Form Usage

If you do not customize `BasicForm`, call the two transform helpers in the page:

```vue
<script setup lang="ts">
import { reactive } from 'vue';
import axios from 'axios';
import JSensitiveInput from '@/components/sensitive/JSensitiveInput.vue';
import {
  hydrateSensitiveFormValues,
  preprocessSensitiveSubmitValues,
  type SensitiveFormSchema,
} from '@/utils/sensitive/transform';

const formSchema: SensitiveFormSchema[] = [
  { field: 'phone', component: 'JSensitiveInput' },
];

const formModel = reactive<Record<string, any>>({});

async function loadDetail(id: string) {
  const response = await axios.get(`/users/${id}`);
  Object.assign(formModel, hydrateSensitiveFormValues(response.data, formSchema));
}

async function save() {
  const payload = preprocessSensitiveSubmitValues(formModel, formSchema, 'meta');
  await axios.post('/users', payload);
}
</script>

<template>
  <a-form :model="formModel">
    <a-form-item label="Phone" name="phone">
      <JSensitiveInput v-model:value="formModel.phone" />
    </a-form-item>
    <a-button type="primary" @click="save">Save</a-button>
  </a-form>
</template>
```

## 5. JEECG `BasicForm` Integration Points

To handle this centrally like `crm-vue`, integrate at these four points.

### 5.1 Register The Component

Add this in `componentMap.ts`:

```ts
import JSensitiveInput from '@/components/sensitive/JSensitiveInput.vue';

componentMap.set('JSensitiveInput', JSensitiveInput);
```

If your project has a `ComponentType` union, add `'JSensitiveInput'` there too.

### 5.2 Add A Form Prop

Add this in `props.ts` or your form props definition:

```ts
import type { PropType } from 'vue';

sensitiveSubmitMode: {
  type: String as PropType<'omit' | 'meta'>,
  default: 'meta',
},
```

Prefer `meta` as the default. `omit` is only suitable for submit paths that do not need backend hydration for unchanged sensitive fields.

### 5.3 Hydrate During Form Assignment

Add this at the beginning of `setFieldsValue`:

```ts
import { hydrateSensitiveFormValues } from '@/utils/sensitive/transform';

async function setFieldsValue(values: Record<string, any>): Promise<void> {
  values = hydrateSensitiveFormValues(values, unref(getSchema));
  // Continue with existing setFieldsValue logic.
}
```

### 5.4 Preprocess Once Before Submit

In `validate()`, `getFieldsValue()`, and `handleSubmit()` paths that return form values, call preprocessing only once.

Recommended shape:

```ts
import { preprocessSensitiveSubmitValues } from '@/utils/sensitive/transform';

const values = await formEl.validate();
return preprocessSensitiveSubmitValues(values, unref(getSchema), unref(getProps).sensitiveSubmitMode);
```

Do not call preprocessing again inside `handleSubmit()` after `validate()` already returned a processed payload. That will drop `sensitiveSubmitMeta` a second time.

## 6. Form Schema Example

```ts
export const formSchema = [
  {
    field: 'phone',
    label: 'Phone',
    component: 'JSensitiveInput',
    componentProps: {
      placeholder: 'Enter phone number',
    },
  },
];
```

With `BasicForm`:

```ts
const [registerForm, { setFieldsValue, validate }] = useForm({
  schemas: formSchema,
  showActionButtonGroup: false,
  sensitiveSubmitMode: 'meta',
});

async function open(record: any) {
  await setFieldsValue(record);
}

async function handleSubmit() {
  const payload = await validate();
  await saveUser(payload);
}
```

## 7. Response Shape

The detail API should return the masked display value and same-field `sensitiveLookupMeta`:

```json
{
  "id": "U-100",
  "name": "Alice",
  "phone": "138****8000",
  "sensitiveLookupMeta": {
    "phone": {
      "sid": "user_account",
      "pid": "phone",
      "vid": "U-100",
      "hash": "7f8c..."
    }
  }
}
```

`hydrateSensitiveFormValues(...)` converts it into the object expected by `JSensitiveInput`:

```ts
{
  phone: {
    value: '138****8000',
    maskedValue: '138****8000',
    lookupMeta: {
      sid: 'user_account',
      pid: 'phone',
      vid: 'U-100',
      hash: '7f8c...',
    },
    state: 'masked',
  },
}
```

## 8. form-urlencoded Submit

If the save endpoint uses `application/x-www-form-urlencoded`, convert the preprocessed object like this:

```ts
const payload = preprocessSensitiveSubmitValues(values, formSchema, 'meta');
const params = new URLSearchParams();

Object.keys(payload).forEach((key) => {
  const value = payload[key];
  if (key !== 'sensitiveSubmitMeta') {
    params.append(key, String(value ?? ''));
    return;
  }
  Object.keys(value).forEach((field) => {
    const meta = value[field];
    params.append(`sensitiveSubmitMeta[${field}][sid]`, meta.sid);
    params.append(`sensitiveSubmitMeta[${field}][pid]`, meta.pid);
    params.append(`sensitiveSubmitMeta[${field}][vid]`, meta.vid);
    params.append(`sensitiveSubmitMeta[${field}][hash]`, meta.hash);
    params.append(`sensitiveSubmitMeta[${field}][state]`, meta.state);
  });
});

await axios.post('/users', params, {
  headers: {
    'Content-Type': 'application/x-www-form-urlencoded',
  },
});
```

The backend hydrates the bracketed structure back into the original plaintext field before controller binding.


## 9. `JSensitiveText` Read-only Display

For a normal page that only needs to show a masked sensitive value and reveal plaintext on demand, use `JSensitiveText`. It does not enter edit mode and does not participate in submit preprocessing; it only manages its own reveal/hide state.

```vue
<script setup lang="ts">
import JSensitiveText from '@/components/sensitive/JSensitiveText.vue';
</script>

<template>
  <JSensitiveText :value="detail.phone" />
</template>
```

The plaintext lookup request sends `sid`, `pid`, `vid`, and `hash`, plus:

- `des`: the current masked value
- `path`: the current route path

If the value is a plain string, it only renders text and does not show the eye icon.
