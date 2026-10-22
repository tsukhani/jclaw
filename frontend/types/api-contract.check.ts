// Compile-time contract (JF-17): every field the hand-written API types read must be one the Java response type sends
// (api-contract.generated.ts, kept current by ApiContractSnapshotTest). A field only Java sends is unread, so it passes.
import type { components } from './api-contract.generated'
import type { Agent, Breaker, Conversation, Message, MessageAttachment, Prompt, ScrapeJob, Task } from './api'
import type { Conversation as ParsedConversation, Message as ParsedMessage, MessageAttachment as ParsedMessageAttachment } from './schemas'

type Java<N extends keyof components['schemas']> = components['schemas'][N]
// An index signature (Task has one) would make every name a key.
type KnownKeys<T> = keyof { [K in keyof T as string extends K ? never : number extends K ? never : K]: T[K] }
type NotSent<Hand, Generated, ClientOnly = never> = Exclude<KnownKeys<Hand>, keyof Generated | ClientOnly>
type Agrees<D extends never> = D

type MessageUiState = '_contextTokens' | '_key' | '_quote' | '_route' | '_thinkingDurationMs' | '_thinkingStartedAt'
  | 'thinkingCollapsed' | 'toolCallsCollapsed'

export type ApiContract = [
  Agrees<NotSent<Message, Java<'MessageView'>, MessageUiState>>,
  Agrees<NotSent<ParsedMessage, Java<'MessageView'>, MessageUiState>>,
  Agrees<NotSent<Conversation, Java<'ConversationView'>>>,
  Agrees<NotSent<ParsedConversation, Java<'ConversationView'>>>,
  Agrees<NotSent<MessageAttachment, Java<'MessageAttachmentView'>>>,
  Agrees<NotSent<ParsedMessageAttachment, Java<'MessageAttachmentView'>>>,
  Agrees<NotSent<Agent, Java<'AgentView'>>>,
  Agrees<NotSent<Task, Java<'TaskView'>>>,
  Agrees<NotSent<ScrapeJob, Java<'ScrapeJobView'>>>,
  Agrees<NotSent<Prompt, Java<'PromptView'>>>,
  Agrees<NotSent<Breaker, Java<'BreakerView'>>>,
]
