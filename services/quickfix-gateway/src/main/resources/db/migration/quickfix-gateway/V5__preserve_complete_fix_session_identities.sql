-- Complete identities combine several bounded QuickFIX/J components and separators.
ALTER TABLE quickfix_gateway.fix_delivery_intents
    ALTER COLUMN session_id TYPE TEXT;
