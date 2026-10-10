import { adminLedgerSnapshot } from './admin-ledger.ts';
const assert = (value: unknown, expected: unknown) => { if (JSON.stringify(value)!==JSON.stringify(expected)) throw new Error(`${JSON.stringify(value)} != ${JSON.stringify(expected)}`); };
Deno.test('Latest correction, reversed payment, archived contacts, and report reconcile',()=>{
 const raw={contacts:[{id:'c',name:'Customer',archived:true}],debts:[{id:'d',contact_id:'c',direction:'receivable',amount_cents:10000,created_at:'2026-10-10T00:00:00Z'},{id:'d2',contact_id:'c',direction:'payable',amount_cents:4000,created_at:'2026-10-10T00:00:00Z'}],
  payments:[{id:'p',debt_id:'d',amount_cents:3000,created_at:'2026-10-10T01:00:00Z'},{id:'p2',debt_id:'d2',amount_cents:1000,created_at:'2026-10-10T01:00:00Z'}],
  adjustments:[{id:'a2',debt_id:'d',amount_cents:11000,previous_amount_cents:12000,version:3,created_at:'2026-10-10T03:00:00Z'},{id:'a1',debt_id:'d',amount_cents:12000,previous_amount_cents:10000,version:2,created_at:'2026-10-10T02:00:00Z'}],
  reversals:[{id:'r',payment_id:'p',reason:'Returned',created_at:'2026-10-10T04:00:00Z'}],requestId:'audit-id'};
 const s=adminLedgerSnapshot(raw,{id:1});
 assert(s.totals,{receivable:110,payable:30,net:80});
 assert(s.contacts[0].archived,true);assert(s.debts[0].paid,0);assert(s.debts[0].originalAmount,100);assert(s.debts[0].amount,110);
 assert(s.payments[0].reversed,true);assert(s.transactions[0].kind,'reversal');assert(s.transactions[0].amount,-30);
 assert(s.transactions.filter((x:Record<string,unknown>)=>x.kind==='debt').map((x:Record<string,unknown>)=>x.amount),[100,40]);assert(s.requestId,'audit-id');
});
Deno.test('Empty initialized ledger remains valid',()=>assert(adminLedgerSnapshot({},{id:1}).totals,{receivable:0,payable:0,net:0}));
