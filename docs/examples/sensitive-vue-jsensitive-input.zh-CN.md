# Vue `JSensitiveInput` 接入示例

这个示例参考 `crm-vue` 中的 `JSensitiveInput.vue`、`utils/sensitive/transform.ts` 与 JEECG `BasicForm` 接入方式，整理成可以直接复制到业务前端项目里的最小实现。

适用前提：

- Vue 3
- ant-design-vue
- axios
- 后端保存接口已标注 `@SensitiveRequestHydration`
- 后端响应 DTO 会返回脱敏字段和 `sensitiveLookupMeta`

## 1. 提交协议

推荐使用 `meta` 模式。用户未修改敏感字段时，前端不提交原字段，而是提交 `sensitiveSubmitMeta[field]`：

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

用户修改敏感字段时，直接提交新明文：

```json
{
  "id": "U-100",
  "name": "Alice",
  "phone": "13900139000"
}
```

后端只在接口显式标注 `@SensitiveRequestHydration` 时处理该协议：

```java
@SensitiveRequestHydration
@PostMapping("/users")
public void save(@RequestBody UserSaveRequest request) {
    userService.save(request);
}
```

这个还原是后端内部转换，不会把明文返回给调用方，也不会触发明文查看审计。

## 2. `src/utils/sensitive/transform.ts`

下面文件不依赖 JEECG 类型，可以直接放到普通 Vue 项目中。若你的项目已有 `FormSchema` 类型，只需要让 schema 至少包含 `field` 和 `component` 两个属性。

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

这个组件保留了 `crm-vue` 的核心行为：

- 初始回显为 `masked`
- 点击眼睛图标时调用显式明文查看接口
- 聚焦脱敏态字段时进入 `changed`，清空旧脱敏值，避免用户误以为是在编辑明文
- `revealed` 只是前端展示态，保存时仍按未修改字段提交 `sensitiveSubmitMeta`

```vue
<template>
  <a-input v-bind="inputAttrs" v-model:value="inputValue" @focus="onFocus">
    <template #suffix>
      <a-tooltip v-if="showLookupButton" title="查看原文">
        <span class="sensitive-eye" @click.stop="onToggleEye">
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

如果你的项目仍使用 JEECG / Vben 风格路径，可以把上面这些点替换回项目内实现：

- `axios.post(...)` 换成项目自己的 `defHttp.post(...)`
- `@/utils/sensitive/transform` 换成 `'/@/utils/sensitive/transform'`
- `@ant-design/icons-vue` 换成项目已有的 `Icon` 组件
- 消息提示可以接入 `useMessage()`，在 `fetchPlainText()` 失败时提示用户

## 4. 普通表单页面用法

不改造 `BasicForm` 时，可以在页面里手动调用两个工具函数：

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
    <a-form-item label="手机号" name="phone">
      <JSensitiveInput v-model:value="formModel.phone" />
    </a-form-item>
    <a-button type="primary" @click="save">保存</a-button>
  </a-form>
</template>
```

## 5. JEECG `BasicForm` 改造点

如果你希望像 `crm-vue` 一样在 `BasicForm` 中统一处理，按下面 4 个位置接入。

### 5.1 注册组件

在 `componentMap.ts` 中加入：

```ts
import JSensitiveInput from '@/components/sensitive/JSensitiveInput.vue';

componentMap.set('JSensitiveInput', JSensitiveInput);
```

如果你的项目有 `ComponentType` 联合类型，也把 `'JSensitiveInput'` 加进去。

### 5.2 增加表单属性

在 `props.ts` 或表单 props 定义中加入：

```ts
sensitiveSubmitMode: {
  type: String as PropType<'omit' | 'meta'>,
  default: 'meta',
},
```

推荐默认值使用 `meta`。`omit` 只适合不需要后端自动还原未修改敏感字段的提交链路。

### 5.3 表单赋值时 hydrate

在 `setFieldsValue` 入口处增加：

```ts
import { hydrateSensitiveFormValues } from '@/utils/sensitive/transform';

async function setFieldsValue(values: Record<string, any>): Promise<void> {
  values = hydrateSensitiveFormValues(values, unref(getSchema));
  // 原有 setFieldsValue 逻辑继续执行
}
```

### 5.4 表单提交前 preprocess

在 `validate()`、`getFieldsValue()`、`handleSubmit()` 这些会返回表单值的位置，统一调用：

```ts
import { preprocessSensitiveSubmitValues } from '@/utils/sensitive/transform';

const values = await formEl.validate();
return preprocessSensitiveSubmitValues(values, unref(getSchema), unref(getProps).sensitiveSubmitMode);
```

注意不要在同一条提交链路重复调用多次。建议把预处理放在最靠近“返回给业务 submit handler”的位置。

## 6. 表单 schema 示例

```ts
export const formSchema = [
  {
    field: 'phone',
    label: '手机号',
    component: 'JSensitiveInput',
    componentProps: {
      placeholder: '请输入手机号',
    },
  },
];
```

使用 `BasicForm` 时：

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

## 7. 响应数据要求

详情接口需要返回脱敏展示值和同名 `sensitiveLookupMeta`：

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

`hydrateSensitiveFormValues(...)` 会把它转换成 `JSensitiveInput` 需要的对象：

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

## 8. form-urlencoded 提交

如果保存接口使用 `application/x-www-form-urlencoded`，`preprocessSensitiveSubmitValues(...)` 得到对象后可以这样转换：

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

后端会在 controller 绑定前把 bracket 结构内部还原为原字段明文。

